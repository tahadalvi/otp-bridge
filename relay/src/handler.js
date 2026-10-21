import { randomBytes, createHash } from 'node:crypto'
import { createWriteStream, createReadStream, readFileSync, existsSync } from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'
import { once } from 'node:events'
import { bearerFrom, tokenMatches } from './auth.js'

const PREFIX = '/otp-bridge'
const MAX_BODY_BYTES = 8 * 1024
const DEFAULT_PAIR_TTL_MS = 7 * 24 * 60 * 60 * 1000
const DEFAULT_PAIR_USES = 5
const MAX_PAIR_USES = 20

export function normalisePath(url) {
  // Named p, not path: the path module is imported at the top of this file.
  const p = (url ?? '/').split('?')[0]
  return p.startsWith(PREFIX) ? (p.slice(PREFIX.length) || '/') : p
}

function json(res, status, obj) {
  const body = JSON.stringify(obj)
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) })
  res.end(body)
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0
    const chunks = []
    req.on('data', c => {
      size += c.length
      if (size > MAX_BODY_BYTES) { reject(new Error('body too large')); req.destroy(); return }
      chunks.push(c)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

function validEntry(o) {
  const str = v => typeof v === 'string' && v.length > 0 && v.length < 1000
  return o && str(o.id) && str(o.code) && str(o.sender) && str(o.receivedAt)
}

export function createHandler({
  store, phoneToken, pcToken, rateLimiter,
  now = Date.now, pairTtlMs = DEFAULT_PAIR_TTL_MS, updateDir = null, stateDir = null,
}) {
  // Published APK. Unlike OTP codes — which must never touch disk here — an app
  // binary is not a secret and has to survive a restart, so it is stored.
  const apkPath = updateDir ? path.join(updateDir, 'latest.apk') : null
  const metaPath = updateDir ? path.join(updateDir, 'latest.json') : null

  let updateMeta = null
  if (metaPath && existsSync(metaPath) && existsSync(apkPath)) {
    try {
      updateMeta = JSON.parse(readFileSync(metaPath, 'utf8'))
    } catch {
      updateMeta = null // corrupt metadata is the same as nothing published
    }
  }
  const clients = new Set() // res objects with an open SSE stream

  // Pairing tokens. The APK ships with one of these instead of the real phone
  // token. Multi-use so a single APK can be installed on several phones, but
  // capped and time-limited so it eventually goes dead — and the cap is
  // enforced here, not by the client agreeing to forget.
  //
  // Persisted, because these are baked into APKs that live for days: holding
  // them only in memory meant every deploy silently bricked pairing for any
  // APK already handed out.
  const pairStatePath = stateDir ? path.join(stateDir, 'pairs.json') : null
  const pairTokens = new Map() // token -> { mintedAt, usesLeft }

  if (pairStatePath && existsSync(pairStatePath)) {
    try {
      for (const [t, e] of Object.entries(JSON.parse(readFileSync(pairStatePath, 'utf8')))) {
        pairTokens.set(t, e)
      }
    } catch {
      // Corrupt state is the same as none; pairing can be re-minted cheaply.
    }
  }

  async function persistPairTokens() {
    if (!pairStatePath) return
    // Drop expired entries while we are here so the file cannot grow forever.
    for (const [t, e] of pairTokens) {
      if (now() - e.mintedAt > pairTtlMs) pairTokens.delete(t)
    }
    await fsp.mkdir(stateDir, { recursive: true })
    const tmp = `${pairStatePath}.tmp`
    await fsp.writeFile(tmp, JSON.stringify(Object.fromEntries(pairTokens)), 'utf8')
    await fsp.rename(tmp, pairStatePath)
  }

  function broadcast(entry) {
    const frame = `event: otp\ndata: ${JSON.stringify(entry)}\n\n`
    for (const res of clients) res.write(frame)
  }

  return async function handler(req, res) {
    const routePath = normalisePath(req.url)
    const presented = bearerFrom(req.headers.authorization)
    const isPhone = tokenMatches(presented, phoneToken)
    const isPc = tokenMatches(presented, pcToken)

    if (routePath === '/healthz' && req.method === 'GET') {
      return json(res, 200, { ok: true, pending: store.size() })
    }

    // Rate limit every authenticated route, keyed by which token was presented.
    if (presented && (isPhone || isPc)) {
      if (!rateLimiter.allow(isPhone ? 'phone' : 'pc', now())) {
        return json(res, 429, { error: 'rate limited' })
      }
    }

    if (routePath === '/otp' && req.method === 'POST') {
      if (!isPhone) return json(res, 401, { error: 'unauthorized' })
      let parsed
      try {
        parsed = JSON.parse(await readBody(req))
      } catch {
        return json(res, 400, { error: 'malformed body' })
      }
      if (!validEntry(parsed)) return json(res, 400, { error: 'invalid entry' })

      const entry = {
        id: parsed.id, code: parsed.code, sender: parsed.sender,
        body: typeof parsed.body === 'string' ? parsed.body : '',
        receivedAt: parsed.receivedAt, storedAt: now(),
      }
      // Use add()'s own return value, not a size comparison: once the ring is
      // full an add evicts as it inserts, leaving size unchanged.
      if (store.add(entry)) broadcast(entry)
      return json(res, 202, { ok: true })
    }

    if (routePath === '/otp/stream' && req.method === 'GET') {
      if (!isPc) return json(res, 401, { error: 'unauthorized' })
      res.writeHead(200, {
        'content-type': 'text/event-stream',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
      })
      res.write('retry: 3000\n\n')
      for (const e of store.pending()) res.write(`event: otp\ndata: ${JSON.stringify(e)}\n\n`)
      clients.add(res)
      const keepAlive = setInterval(() => res.write(': ping\n\n'), 20000)
      const drop = () => { clearInterval(keepAlive); clients.delete(res) }
      req.on('close', drop)
      req.on('error', drop)
      return
    }

    if (routePath === '/pair/mint' && req.method === 'POST') {
      if (!isPc) return json(res, 401, { error: 'unauthorized' })
      let requested
      try {
        requested = JSON.parse(await readBody(req) || '{}')
      } catch {
        return json(res, 400, { error: 'malformed body' })
      }
      const asked = Number(requested?.uses)
      const uses = Number.isFinite(asked) && asked >= 1
        ? Math.min(Math.floor(asked), MAX_PAIR_USES)
        : DEFAULT_PAIR_USES

      const token = randomBytes(32).toString('base64url')
      pairTokens.set(token, { mintedAt: now(), usesLeft: uses })
      await persistPairTokens()
      return json(res, 200, { pairToken: token, uses, expiresInMs: pairTtlMs })
    }

    if (routePath === '/pair/claim' && req.method === 'POST') {
      // Deliberately unauthenticated: the pairing token IS the credential.
      // Rate limited by source-agnostic bucket so it cannot be brute forced —
      // a 32-byte token is unguessable, but there is no reason to allow trying.
      if (!rateLimiter.allow('pair-claim', now())) {
        return json(res, 429, { error: 'rate limited' })
      }
      let parsed
      try {
        parsed = JSON.parse(await readBody(req))
      } catch {
        return json(res, 400, { error: 'malformed body' })
      }
      const presentedPair = parsed?.pairToken
      if (typeof presentedPair !== 'string' || presentedPair.length === 0) {
        return json(res, 400, { error: 'pairToken required' })
      }

      const entry = pairTokens.get(presentedPair)
      if (entry === undefined) {
        return json(res, 401, { error: 'invalid or expired pairing token' })
      }
      if (now() - entry.mintedAt > pairTtlMs) {
        pairTokens.delete(presentedPair)
        await persistPairTokens()
        return json(res, 401, { error: 'invalid or expired pairing token' })
      }

      entry.usesLeft -= 1
      if (entry.usesLeft <= 0) pairTokens.delete(presentedPair)
      // Persist before responding: otherwise a crash between the two would let
      // the same use be claimed again.
      await persistPairTokens()
      return json(res, 200, { phoneToken, usesLeft: Math.max(0, entry.usesLeft) })
    }

    // --- app updates ---------------------------------------------------
    // The phone polls this instead of needing a cable. Android refuses an
    // in-place update signed with a different key, so this channel can only
    // ever deliver builds signed with the project keystore.

    if (routePath === '/update/apk' && req.method === 'PUT') {
      if (!isPc) return json(res, 401, { error: 'unauthorized' })
      if (!updateDir) return json(res, 503, { error: 'no update directory configured' })

      const params = new URL(req.url, 'http://x').searchParams
      const versionCode = Number(params.get('versionCode'))
      const versionName = params.get('versionName') ?? ''
      if (!Number.isInteger(versionCode) || versionCode < 1) {
        return json(res, 400, { error: 'versionCode must be a positive integer' })
      }

      await fsp.mkdir(updateDir, { recursive: true })
      const tmp = `${apkPath}.tmp`
      const hash = createHash('sha256')
      const out = createWriteStream(tmp)
      let size = 0
      try {
        for await (const chunk of req) {
          hash.update(chunk)
          size += chunk.length
          if (!out.write(chunk)) await once(out, 'drain')
        }
        await new Promise((resolve, reject) => out.end(err => (err ? reject(err) : resolve())))
      } catch (err) {
        out.destroy()
        await fsp.rm(tmp, { force: true })
        return json(res, 400, { error: `upload failed: ${err.message}` })
      }

      if (size === 0) {
        await fsp.rm(tmp, { force: true })
        return json(res, 400, { error: 'empty body' })
      }

      await fsp.rename(tmp, apkPath)
      const meta = {
        versionCode, versionName, size,
        sha256: hash.digest('hex'),
        publishedAt: new Date(now()).toISOString(),
      }
      await fsp.writeFile(metaPath, JSON.stringify(meta, null, 2) + '\n', 'utf8')
      updateMeta = meta
      return json(res, 200, meta)
    }

    if (routePath === '/update/latest' && req.method === 'GET') {
      if (!isPhone && !isPc) return json(res, 401, { error: 'unauthorized' })
      if (!updateMeta) return json(res, 404, { error: 'nothing published' })
      return json(res, 200, updateMeta)
    }

    if (routePath === '/update/apk' && req.method === 'GET') {
      if (!isPhone && !isPc) return json(res, 401, { error: 'unauthorized' })
      if (!updateMeta || !existsSync(apkPath)) return json(res, 404, { error: 'nothing published' })
      res.writeHead(200, {
        'content-type': 'application/vnd.android.package-archive',
        'content-length': updateMeta.size,
        'x-sha256': updateMeta.sha256,
      })
      createReadStream(apkPath).pipe(res)
      return
    }

    const ackMatch = /^\/otp\/([^/]+)\/ack$/.exec(routePath)
    if (ackMatch && req.method === 'POST') {
      if (!isPc) return json(res, 401, { error: 'unauthorized' })
      return json(res, 200, { ok: store.ack(decodeURIComponent(ackMatch[1])) })
    }

    return json(res, 404, { error: 'not found' })
  }
}

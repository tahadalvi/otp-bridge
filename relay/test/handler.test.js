import { test } from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import fsp from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { createHandler, normalisePath } from '../src/handler.js'
import { createStore } from '../src/store.js'
import { createRateLimiter } from '../src/auth.js'

const PHONE = 'phone-token-aaaaaaaaaaaaaaaaaaaaaaaa'
const PC = 'pc-token-bbbbbbbbbbbbbbbbbbbbbbbbbbbb'

const payload = (id = 'id-1') => ({
  id, code: '483920', sender: 'VM-YOURBANK',
  body: '483920 is your OTP.', receivedAt: '2026-07-30T14:30:22.000+05:30',
})

async function withServer(fn, opts = {}) {
  const store = opts.store ?? createStore({})
  const handler = createHandler({
    store, phoneToken: PHONE, pcToken: PC,
    rateLimiter: opts.rateLimiter ?? createRateLimiter({ limit: 1000, windowMs: 60000 }),
  })
  const server = http.createServer(handler)
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  const base = `http://127.0.0.1:${server.address().port}`
  try { return await fn(base, store) } finally { server.close() }
}

const post = (base, path, token, body) => fetch(base + path, {
  method: 'POST',
  headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
  body: JSON.stringify(body),
})

test('normalisePath strips the funnel prefix', () => {
  assert.equal(normalisePath('/otp-bridge/otp'), '/otp')
  assert.equal(normalisePath('/otp'), '/otp')
  assert.equal(normalisePath('/otp-bridge/otp/x/ack'), '/otp/x/ack')
  assert.equal(normalisePath('/otp?q=1'), '/otp')
})

test('healthz needs no auth', async () => {
  await withServer(async base => {
    const r = await fetch(base + '/healthz')
    assert.equal(r.status, 200)
    assert.equal((await r.json()).ok, true)
  })
})

test('POST /otp stores an entry with a valid phone token', async () => {
  await withServer(async (base, store) => {
    const r = await post(base, '/otp', PHONE, payload())
    assert.equal(r.status, 202)
    assert.equal(store.size(), 1)
    assert.equal(store.pending()[0].code, '483920')
  })
})

test('POST /otp works through the funnel path prefix', async () => {
  await withServer(async (base, store) => {
    const r = await post(base, '/otp-bridge/otp', PHONE, payload())
    assert.equal(r.status, 202)
    assert.equal(store.size(), 1)
  })
})

test('POST /otp rejects a missing token', async () => {
  await withServer(async (base, store) => {
    assert.equal((await post(base, '/otp', null, payload())).status, 401)
    assert.equal(store.size(), 0)
  })
})

test('POST /otp rejects the PC token — scopes are not interchangeable', async () => {
  await withServer(async (base, store) => {
    assert.equal((await post(base, '/otp', PC, payload())).status, 401)
    assert.equal(store.size(), 0)
  })
})

test('POST /otp rejects a malformed body', async () => {
  await withServer(async (base, store) => {
    const r = await post(base, '/otp', PHONE, { id: 'x' }) // no code
    assert.equal(r.status, 400)
    assert.equal(store.size(), 0)
  })
})

test('POST /otp is idempotent on id', async () => {
  await withServer(async (base, store) => {
    await post(base, '/otp', PHONE, payload('same'))
    await post(base, '/otp', PHONE, payload('same'))
    assert.equal(store.size(), 1)
  })
})

test('rate limit returns 429 once exceeded', async () => {
  const rateLimiter = createRateLimiter({ limit: 2, windowMs: 60000 })
  await withServer(async base => {
    await post(base, '/otp', PHONE, payload('a'))
    await post(base, '/otp', PHONE, payload('b'))
    assert.equal((await post(base, '/otp', PHONE, payload('c'))).status, 429)
  }, { rateLimiter })
})

test('ack removes an entry with the PC token', async () => {
  await withServer(async (base, store) => {
    await post(base, '/otp', PHONE, payload('gone'))
    const r = await post(base, '/otp/gone/ack', PC, {})
    assert.equal(r.status, 200)
    assert.equal(store.size(), 0)
  })
})

test('ack rejects the phone token', async () => {
  await withServer(async (base, store) => {
    await post(base, '/otp', PHONE, payload('stay'))
    assert.equal((await post(base, '/otp/stay/ack', PHONE, {})).status, 401)
    assert.equal(store.size(), 1)
  })
})

test('stream rejects the phone token', async () => {
  await withServer(async base => {
    const r = await fetch(base + '/otp/stream', { headers: { authorization: `Bearer ${PHONE}` } })
    assert.equal(r.status, 401)
    await r.text()
  })
})

test('stream replays pending entries then pushes new ones', async () => {
  await withServer(async (base, store) => {
    await post(base, '/otp', PHONE, payload('first'))

    const ac = new AbortController()
    const r = await fetch(base + '/otp/stream', {
      headers: { authorization: `Bearer ${PC}` }, signal: ac.signal,
    })
    assert.equal(r.status, 200)
    assert.match(r.headers.get('content-type'), /text\/event-stream/)

    const reader = r.body.getReader()
    const decoder = new TextDecoder()
    let buf = ''

    const readUntil = async (needle) => {
      while (!buf.includes(needle)) {
        const { value, done } = await reader.read()
        if (done) throw new Error('stream ended early')
        buf += decoder.decode(value, { stream: true })
      }
    }

    await readUntil('"first"')
    await post(base, '/otp', PHONE, payload('second'))
    await readUntil('"second"')

    assert.match(buf, /event: otp/)
    ac.abort()
  })
})

test('unknown route returns 404', async () => {
  await withServer(async base => {
    assert.equal((await fetch(base + '/nope')).status, 404)
  })
})

// --- one-time pairing ------------------------------------------------------
// The APK ships with a pairing token instead of the real phone token. First
// launch trades it in; the relay burns it. Replay must fail at the server, not
// by the client agreeing to forget.

test('mint requires the PC token', async () => {
  await withServer(async base => {
    assert.equal((await post(base, '/pair/mint', null, {})).status, 401)
    assert.equal((await post(base, '/pair/mint', PHONE, {})).status, 401)
  })
})

test('mint returns a pairing token', async () => {
  await withServer(async base => {
    const r = await post(base, '/pair/mint', PC, {})
    assert.equal(r.status, 200)
    const { pairToken } = await r.json()
    assert.equal(typeof pairToken, 'string')
    assert.ok(pairToken.length >= 32, 'pairing token should be long enough to resist guessing')
  })
})

test('claim exchanges a pairing token for the phone token', async () => {
  await withServer(async base => {
    const { pairToken } = await (await post(base, '/pair/mint', PC, {})).json()
    const r = await post(base, '/pair/claim', null, { pairToken })
    assert.equal(r.status, 200)
    const body = await r.json()
    assert.equal(body.phoneToken, PHONE)
  })
})

test('claim needs no bearer token — the pairing token is the credential', async () => {
  await withServer(async base => {
    const { pairToken } = await (await post(base, '/pair/mint', PC, {})).json()
    assert.equal((await post(base, '/pair/claim', null, { pairToken })).status, 200)
  })
})

test('a pairing token works for several phones, up to its cap', async () => {
  await withServer(async base => {
    const { pairToken, uses } = await (await post(base, '/pair/mint', PC, { uses: 3 })).json()
    assert.equal(uses, 3)
    for (let i = 0; i < 3; i++) {
      assert.equal(
        (await post(base, '/pair/claim', null, { pairToken })).status, 200,
        `claim ${i + 1} of 3 should succeed — one APK, several phones`,
      )
    }
    assert.equal(
      (await post(base, '/pair/claim', null, { pairToken })).status, 401,
      'past the cap the server must refuse, so a leaked APK goes dead',
    )
  })
})

test('mint defaults to a small but usable number of pairings', async () => {
  await withServer(async base => {
    const { uses } = await (await post(base, '/pair/mint', PC, {})).json()
    assert.ok(uses >= 2 && uses <= 20, `default uses should be sane, got ${uses}`)
  })
})

test('mint clamps an absurd uses request', async () => {
  await withServer(async base => {
    const { uses } = await (await post(base, '/pair/mint', PC, { uses: 100000 })).json()
    assert.ok(uses <= 20, `uses should be clamped, got ${uses}`)
  })
})

test('pairing tokens survive a relay restart', async () => {
  // Regression: they used to live only in memory, so any deploy silently
  // invalidated every APK already in someone's hands.
  const stateDir = await fsp.mkdtemp(path.join(os.tmpdir(), 'otp-pairs-'))
  const mk = () => createHandler({
    store: createStore({}), phoneToken: PHONE, pcToken: PC,
    rateLimiter: createRateLimiter({ limit: 1000, windowMs: 60000 }),
    stateDir,
  })

  let server = http.createServer(mk())
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  let base = `http://127.0.0.1:${server.address().port}`
  const { pairToken } = await (await post(base, '/pair/mint', PC, { uses: 2 })).json()
  server.close()

  server = http.createServer(mk())
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  base = `http://127.0.0.1:${server.address().port}`
  try {
    const r = await post(base, '/pair/claim', null, { pairToken })
    assert.equal(r.status, 200, 'a token minted before a restart must still work after it')
    assert.equal((await r.json()).phoneToken, PHONE)
  } finally { server.close() }
})

test('a use spent before a restart stays spent', async () => {
  const stateDir = await fsp.mkdtemp(path.join(os.tmpdir(), 'otp-pairs-'))
  const mk = () => createHandler({
    store: createStore({}), phoneToken: PHONE, pcToken: PC,
    rateLimiter: createRateLimiter({ limit: 1000, windowMs: 60000 }),
    stateDir,
  })

  let server = http.createServer(mk())
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  let base = `http://127.0.0.1:${server.address().port}`
  const { pairToken } = await (await post(base, '/pair/mint', PC, { uses: 1 })).json()
  assert.equal((await post(base, '/pair/claim', null, { pairToken })).status, 200)
  server.close()

  server = http.createServer(mk())
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  base = `http://127.0.0.1:${server.address().port}`
  try {
    assert.equal(
      (await post(base, '/pair/claim', null, { pairToken })).status, 401,
      'a restart must not resurrect a spent pairing token',
    )
  } finally { server.close() }
})

test('mint rejects a nonsensical uses value', async () => {
  await withServer(async base => {
    const { uses } = await (await post(base, '/pair/mint', PC, { uses: 0 })).json()
    assert.ok(uses >= 1, 'zero uses would mint a dead token')
  })
})

test('claim rejects an unknown pairing token', async () => {
  await withServer(async base => {
    assert.equal((await post(base, '/pair/claim', null, { pairToken: 'made-up' })).status, 401)
  })
})

test('claim rejects a missing pairing token', async () => {
  await withServer(async base => {
    assert.equal((await post(base, '/pair/claim', null, {})).status, 400)
  })
})

test('an expired pairing token cannot be claimed', async () => {
  let clock = 1000
  const store = createStore({})
  const handler = createHandler({
    store, phoneToken: PHONE, pcToken: PC,
    rateLimiter: createRateLimiter({ limit: 1000, windowMs: 60000 }),
    now: () => clock,
    pairTtlMs: 5000,
  })
  const server = http.createServer(handler)
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  const base = `http://127.0.0.1:${server.address().port}`
  try {
    const { pairToken } = await (await post(base, '/pair/mint', PC, {})).json()
    clock += 6000
    assert.equal((await post(base, '/pair/claim', null, { pairToken })).status, 401)
  } finally { server.close() }
})

import { test } from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { createHash } from 'node:crypto'
import { createHandler } from '../src/handler.js'
import { createStore } from '../src/store.js'
import { createRateLimiter } from '../src/auth.js'

const PHONE = 'phone-token-aaaaaaaaaaaaaaaaaaaaaaaa'
const PC = 'pc-token-bbbbbbbbbbbbbbbbbbbbbbbbbbbb'

// Not a real APK — the relay treats it as opaque bytes, which is the point.
const FAKE_APK = Buffer.from('PK' + 'x'.repeat(5000), 'binary')
const sha256 = b => createHash('sha256').update(b).digest('hex')

async function withServer(fn) {
  const updateDir = await fs.mkdtemp(path.join(os.tmpdir(), 'otp-update-'))
  const handler = createHandler({
    store: createStore({}), phoneToken: PHONE, pcToken: PC,
    rateLimiter: createRateLimiter({ limit: 1000, windowMs: 60000 }),
    updateDir,
  })
  const server = http.createServer(handler)
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  const base = `http://127.0.0.1:${server.address().port}`
  try { return await fn(base, updateDir) } finally { server.close() }
}

const publish = (base, token, body, { code = 2, name = '1.1' } = {}) =>
  fetch(`${base}/update/apk?versionCode=${code}&versionName=${name}`, {
    method: 'PUT',
    headers: { ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body,
  })

const get = (base, p, token) => fetch(base + p, {
  headers: { ...(token ? { authorization: `Bearer ${token}` } : {}) },
})

test('latest reports nothing published yet', async () => {
  await withServer(async base => {
    const r = await get(base, '/update/latest', PHONE)
    assert.equal(r.status, 404)
  })
})

test('publishing requires the PC token', async () => {
  await withServer(async base => {
    assert.equal((await publish(base, null, FAKE_APK)).status, 401)
    assert.equal((await publish(base, PHONE, FAKE_APK)).status, 401)
  })
})

test('publish then latest reports the metadata', async () => {
  await withServer(async base => {
    assert.equal((await publish(base, PC, FAKE_APK, { code: 7, name: '1.7' })).status, 200)
    const meta = await (await get(base, '/update/latest', PHONE)).json()
    assert.equal(meta.versionCode, 7)
    assert.equal(meta.versionName, '1.7')
    assert.equal(meta.size, FAKE_APK.length)
    assert.equal(meta.sha256, sha256(FAKE_APK))
  })
})

test('the phone can download exactly the bytes that were published', async () => {
  await withServer(async base => {
    await publish(base, PC, FAKE_APK)
    const r = await get(base, '/update/apk', PHONE)
    assert.equal(r.status, 200)
    const got = Buffer.from(await r.arrayBuffer())
    assert.equal(got.length, FAKE_APK.length)
    assert.equal(sha256(got), sha256(FAKE_APK), 'bytes must survive the round trip intact')
  })
})

test('downloading the APK requires the phone token', async () => {
  await withServer(async base => {
    await publish(base, PC, FAKE_APK)
    assert.equal((await get(base, '/update/apk', null)).status, 401)
    assert.equal((await get(base, '/update/latest', null)).status, 401)
  })
})

test('the PC token may also read, so publishing can be verified', async () => {
  await withServer(async base => {
    await publish(base, PC, FAKE_APK)
    assert.equal((await get(base, '/update/latest', PC)).status, 200)
  })
})

test('publishing again replaces the previous build', async () => {
  await withServer(async base => {
    await publish(base, PC, FAKE_APK, { code: 2, name: '1.1' })
    const second = Buffer.from('PK' + 'y'.repeat(9000), 'binary')
    await publish(base, PC, second, { code: 3, name: '1.2' })
    const meta = await (await get(base, '/update/latest', PHONE)).json()
    assert.equal(meta.versionCode, 3)
    assert.equal(meta.sha256, sha256(second))
    const got = Buffer.from(await (await get(base, '/update/apk', PHONE)).arrayBuffer())
    assert.equal(sha256(got), sha256(second))
  })
})

test('publish rejects a missing or nonsensical versionCode', async () => {
  await withServer(async base => {
    const noCode = await fetch(`${base}/update/apk?versionName=1.1`, {
      method: 'PUT', headers: { authorization: `Bearer ${PC}` }, body: FAKE_APK,
    })
    assert.equal(noCode.status, 400)
    assert.equal((await publish(base, PC, FAKE_APK, { code: 'abc' })).status, 400)
  })
})

test('publish rejects an empty body', async () => {
  await withServer(async base => {
    assert.equal((await publish(base, PC, Buffer.alloc(0))).status, 400)
  })
})

test('the published APK survives on disk, not just in memory', async () => {
  await withServer(async (base, updateDir) => {
    await publish(base, PC, FAKE_APK)
    const onDisk = await fs.readFile(path.join(updateDir, 'latest.apk'))
    assert.equal(sha256(onDisk), sha256(FAKE_APK))
    const meta = JSON.parse(await fs.readFile(path.join(updateDir, 'latest.json'), 'utf8'))
    assert.equal(meta.sha256, sha256(FAKE_APK))
  })
})

test('a relay restart still serves the last published build', async () => {
  const updateDir = await fs.mkdtemp(path.join(os.tmpdir(), 'otp-update-'))
  const mk = () => createHandler({
    store: createStore({}), phoneToken: PHONE, pcToken: PC,
    rateLimiter: createRateLimiter({ limit: 1000, windowMs: 60000 }),
    updateDir,
  })

  let server = http.createServer(mk())
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  let base = `http://127.0.0.1:${server.address().port}`
  await publish(base, PC, FAKE_APK, { code: 9, name: '1.9' })
  server.close()

  // Fresh handler over the same directory — as after `systemctl restart`.
  server = http.createServer(mk())
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  base = `http://127.0.0.1:${server.address().port}`
  try {
    const meta = await (await get(base, '/update/latest', PHONE)).json()
    assert.equal(meta.versionCode, 9, 'metadata must be read back from disk on start')
  } finally { server.close() }
})

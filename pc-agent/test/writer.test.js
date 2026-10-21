import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import { writeLatest } from '../src/writer.js'

const entry = {
  id: 'id-1', code: '483920', sender: 'VM-YOURBANK',
  body: '483920 is your OTP.', receivedAt: '2026-07-30T14:30:22.000+05:30',
  storedAt: 1234567890,
}

async function tmpTarget() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'otp-writer-'))
  return path.join(dir, 'latest.json')
}

test('writes the expected document', async () => {
  const target = await tmpTarget()
  await writeLatest(target, entry, '2026-07-30T14:30:23.000+05:30')
  const doc = JSON.parse(await fs.readFile(target, 'utf8'))
  assert.equal(doc.code, '483920')
  assert.equal(doc.sender, 'VM-YOURBANK')
  assert.equal(doc.receivedAt, '2026-07-30T14:30:22.000+05:30')
  assert.equal(doc.deliveredAt, '2026-07-30T14:30:23.000+05:30')
  assert.equal(doc.body, '483920 is your OTP.')
})

test('does not leak the internal storedAt field', async () => {
  const target = await tmpTarget()
  await writeLatest(target, entry, '2026-07-30T14:30:23.000+05:30')
  const doc = JSON.parse(await fs.readFile(target, 'utf8'))
  assert.equal('storedAt' in doc, false)
})

test('creates the target directory if it does not exist', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'otp-writer-'))
  const target = path.join(dir, 'nested', 'deeper', 'latest.json')
  await writeLatest(target, entry, '2026-07-30T14:30:23.000+05:30')
  assert.equal(JSON.parse(await fs.readFile(target, 'utf8')).code, '483920')
})

test('overwrites a previous document', async () => {
  const target = await tmpTarget()
  await writeLatest(target, entry, '2026-07-30T14:30:23.000+05:30')
  await writeLatest(target, { ...entry, id: 'id-2', code: '999888' }, '2026-07-30T14:31:00.000+05:30')
  const doc = JSON.parse(await fs.readFile(target, 'utf8'))
  assert.equal(doc.code, '999888')
})

test('leaves no temp file behind', async () => {
  const target = await tmpTarget()
  await writeLatest(target, entry, '2026-07-30T14:30:23.000+05:30')
  const files = await fs.readdir(path.dirname(target))
  assert.deepEqual(files, ['latest.json'])
})

test('reports an atomic swap when nothing holds the file open', async () => {
  const target = await tmpTarget()
  const r = await writeLatest(target, entry, '2026-07-30T14:30:23.000+05:30')
  assert.equal(r.atomic, true)
})

test('a concurrent reader never sees a partial document', async () => {
  const target = await tmpTarget()
  await writeLatest(target, entry, '2026-07-30T14:30:23.000+05:30')

  let stop = false
  let ok = 0
  let lockErrors = 0
  const partials = []

  const reader = (async () => {
    while (!stop) {
      try {
        const doc = JSON.parse(await fs.readFile(target, 'utf8'))
        assert.match(doc.code, /^\d{6}$/)
        ok++
      } catch (e) {
        // A SyntaxError means the reader observed a half-written file. That is
        // exactly the bug the write-then-rename dance exists to prevent.
        if (e instanceof SyntaxError) partials.push(e.message)
        // ENOENT/EPERM/EBUSY are Windows transients while the rename swaps the
        // inode. Unpleasant but not a torn read — the consumer just retries.
        else if (['ENOENT', 'EPERM', 'EBUSY'].includes(e.code)) lockErrors++
        else throw e
      }
      // A 2ms gap. Aggressive enough to interleave with almost every write, but
      // not a zero-gap loop — on Windows an unbroken reader holds a handle
      // permanently and no rename can ever land. See the note in writer.js.
      await new Promise(r => setTimeout(r, 2))
    }
  })()

  for (let i = 0; i < 50; i++) {
    await writeLatest(target, { ...entry, code: String(100000 + i) }, '2026-07-30T14:31:00.000+05:30')
  }
  stop = true
  await reader

  assert.equal(partials.length, 0, `reader observed ${partials.length} torn document(s)`)
  assert.ok(ok > 0, 'reader should have completed at least one successful read')
})

test('writing succeeds even while a reader holds the file open', async () => {
  // Regression guard for the Windows-only failure mode: rename over a file that
  // another process has open returns EPERM. Without the retry in writeLatest,
  // a consumer polling latest.json makes deliveries fail outright.
  const target = await tmpTarget()
  await writeLatest(target, entry, '2026-07-30T14:30:23.000+05:30')

  let stop = false
  const reader = (async () => {
    while (!stop) {
      await fs.readFile(target, 'utf8').catch(() => {})
      // A consumer polling every 10ms is already far more aggressive than any
      // real script. It must not be able to block a delivery.
      await new Promise(r => setTimeout(r, 10))
    }
  })()

  // Every one of these must land. Any throw fails the test.
  for (let i = 0; i < 25; i++) {
    await writeLatest(target, { ...entry, code: String(200000 + i) }, '2026-07-30T14:31:00.000+05:30')
  }
  stop = true
  await reader

  assert.equal(JSON.parse(await fs.readFile(target, 'utf8')).code, '200024')
  assert.deepEqual(await fs.readdir(path.dirname(target)), ['latest.json'])
})

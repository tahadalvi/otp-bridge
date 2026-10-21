import { test } from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'
import { parseFrames, backoffMs, connect } from '../src/sse.js'

test('parseFrames reads one complete frame', () => {
  const { events, rest } = parseFrames('event: otp\ndata: {"a":1}\n\n')
  assert.deepEqual(events, [{ event: 'otp', data: '{"a":1}' }])
  assert.equal(rest, '')
})

test('parseFrames reads two frames in one buffer', () => {
  const { events } = parseFrames('event: otp\ndata: 1\n\nevent: otp\ndata: 2\n\n')
  assert.deepEqual(events.map(e => e.data), ['1', '2'])
})

test('parseFrames retains an incomplete trailing frame', () => {
  const { events, rest } = parseFrames('event: otp\ndata: 1\n\nevent: otp\ndata: par')
  assert.equal(events.length, 1)
  assert.equal(rest, 'event: otp\ndata: par')
})

test('parseFrames ignores comment keep-alives', () => {
  const { events } = parseFrames(': ping\n\nevent: otp\ndata: 1\n\n')
  assert.deepEqual(events.map(e => e.data), ['1'])
})

test('parseFrames ignores a retry directive', () => {
  const { events } = parseFrames('retry: 3000\n\n')
  assert.deepEqual(events, [])
})

test('backoff doubles from 1s and caps at 30s', () => {
  assert.equal(backoffMs(0), 1000)
  assert.equal(backoffMs(1), 2000)
  assert.equal(backoffMs(2), 4000)
  assert.equal(backoffMs(10), 30000)
})

test('connect delivers events and reconnects after the server drops', async () => {
  let connections = 0
  const server = http.createServer((req, res) => {
    assert.equal(req.headers.authorization, 'Bearer pc-token')
    connections++
    res.writeHead(200, { 'content-type': 'text/event-stream' })
    res.write(`event: otp\ndata: {"id":"c${connections}"}\n\n`)
    // Drop the first connection to force a reconnect.
    if (connections === 1) setTimeout(() => res.destroy(), 30)
  })
  await new Promise(r => server.listen(0, '127.0.0.1', r))
  const url = `http://127.0.0.1:${server.address().port}/otp/stream`

  const seen = []
  const ac = new AbortController()
  const done = connect({
    url, token: 'pc-token', signal: ac.signal,
    onEvent: e => seen.push(JSON.parse(e.data).id),
    onStatus: () => {},
    backoff: () => 10, // keep the test fast
  })

  const deadline = Date.now() + 5000
  while (seen.length < 2 && Date.now() < deadline) await new Promise(r => setTimeout(r, 20))
  ac.abort()
  await done
  server.close()

  assert.deepEqual(seen.slice(0, 2), ['c1', 'c2'])
  assert.ok(connections >= 2, 'should have reconnected at least once')
})

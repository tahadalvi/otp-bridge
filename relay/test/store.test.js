import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createStore } from '../src/store.js'

const entry = (id, storedAt = 0) => ({
  id, code: '483920', sender: 'VM-YOURBANK',
  body: '483920 is your OTP.', receivedAt: '2026-07-30T14:30:22.000+05:30',
  storedAt,
})

test('add then pending returns the entry', () => {
  const s = createStore({})
  s.add(entry('a'))
  assert.equal(s.size(), 1)
  assert.equal(s.pending()[0].id, 'a')
})

test('ack removes the entry and reports success', () => {
  const s = createStore({})
  s.add(entry('a'))
  assert.equal(s.ack('a'), true)
  assert.equal(s.size(), 0)
})

test('ack of an unknown id reports failure without throwing', () => {
  const s = createStore({})
  assert.equal(s.ack('nope'), false)
})

test('adding the same id twice does not duplicate', () => {
  const s = createStore({})
  s.add(entry('a'))
  s.add(entry('a'))
  assert.equal(s.size(), 1)
})

test('add reports whether the entry was new', () => {
  const s = createStore({})
  assert.equal(s.add(entry('a')), true)
  assert.equal(s.add(entry('a')), false)
})

test('add still reports true when the ring is already full', () => {
  // Regression guard: size() is unchanged when adding evicts, so callers must
  // not infer "was this new?" from the size before and after.
  const s = createStore({ maxEntries: 1 })
  s.add(entry('a'))
  assert.equal(s.add(entry('b')), true)
  assert.equal(s.size(), 1)
})

test('ring evicts the oldest entry past maxEntries', () => {
  const s = createStore({ maxEntries: 2 })
  s.add(entry('a')); s.add(entry('b')); s.add(entry('c'))
  assert.equal(s.size(), 2)
  assert.deepEqual(s.pending().map(e => e.id), ['b', 'c'])
})

test('sweep removes entries older than the ttl and returns the count', () => {
  const s = createStore({ ttlMs: 1000 })
  s.add(entry('old', 0))
  s.add(entry('new', 900))
  assert.equal(s.sweep(1500), 1)
  assert.deepEqual(s.pending().map(e => e.id), ['new'])
})

test('sweep leaves an entry exactly at the ttl boundary', () => {
  const s = createStore({ ttlMs: 1000 })
  s.add(entry('edge', 0))
  assert.equal(s.sweep(1000), 0)
  assert.equal(s.size(), 1)
})

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { bearerFrom, tokenMatches, createRateLimiter } from '../src/auth.js'

test('bearerFrom extracts the token', () => {
  assert.equal(bearerFrom('Bearer abc123'), 'abc123')
})

test('bearerFrom is case-insensitive on the scheme', () => {
  assert.equal(bearerFrom('bearer abc123'), 'abc123')
})

test('bearerFrom returns null for junk', () => {
  assert.equal(bearerFrom(undefined), null)
  assert.equal(bearerFrom(''), null)
  assert.equal(bearerFrom('Basic abc123'), null)
  assert.equal(bearerFrom('Bearer'), null)
})

test('tokenMatches accepts an identical token', () => {
  assert.equal(tokenMatches('s3cret', 's3cret'), true)
})

test('tokenMatches rejects a different token of equal length', () => {
  assert.equal(tokenMatches('s3cret', 's3crXt'), false)
})

test('tokenMatches rejects on length mismatch without throwing', () => {
  assert.equal(tokenMatches('short', 'muchlongertoken'), false)
})

test('tokenMatches rejects null or empty', () => {
  assert.equal(tokenMatches(null, 's3cret'), false)
  assert.equal(tokenMatches('', ''), false)
})

test('rate limiter allows up to the limit then blocks', () => {
  const rl = createRateLimiter({ limit: 3, windowMs: 1000 })
  assert.equal(rl.allow('k', 0), true)
  assert.equal(rl.allow('k', 10), true)
  assert.equal(rl.allow('k', 20), true)
  assert.equal(rl.allow('k', 30), false)
})

test('rate limiter window slides', () => {
  const rl = createRateLimiter({ limit: 2, windowMs: 1000 })
  rl.allow('k', 0); rl.allow('k', 100)
  assert.equal(rl.allow('k', 200), false)
  assert.equal(rl.allow('k', 1100), true)
})

test('rate limiter tracks keys independently', () => {
  const rl = createRateLimiter({ limit: 1, windowMs: 1000 })
  assert.equal(rl.allow('a', 0), true)
  assert.equal(rl.allow('b', 0), true)
  assert.equal(rl.allow('a', 0), false)
})

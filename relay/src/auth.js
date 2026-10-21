import { timingSafeEqual } from 'node:crypto'

export function bearerFrom(headerValue) {
  if (typeof headerValue !== 'string') return null
  const m = /^Bearer[ ]+(.+)$/i.exec(headerValue.trim())
  return m ? m[1] : null
}

export function tokenMatches(presented, expected) {
  if (typeof presented !== 'string' || typeof expected !== 'string') return false
  if (presented.length === 0 || expected.length === 0) return false
  const a = Buffer.from(presented, 'utf8')
  const b = Buffer.from(expected, 'utf8')
  // timingSafeEqual throws on length mismatch, so guard first. The length of a
  // token is not the secret; its contents are.
  if (a.length !== b.length) return false
  return timingSafeEqual(a, b)
}

export function createRateLimiter({ limit = 30, windowMs = 60000 } = {}) {
  const hits = new Map() // key -> number[] of timestamps

  return {
    allow(key, nowMs) {
      const cutoff = nowMs - windowMs
      const recent = (hits.get(key) ?? []).filter(t => t > cutoff)
      if (recent.length >= limit) { hits.set(key, recent); return false }
      recent.push(nowMs)
      hits.set(key, recent)
      return true
    },
  }
}

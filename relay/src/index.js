import http from 'node:http'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createStore } from './store.js'
import { createRateLimiter } from './auth.js'
import { createHandler } from './handler.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const UPDATE_DIR = process.env.OTP_UPDATE_DIR ?? path.resolve(HERE, '..', 'updates')
const STATE_DIR = process.env.OTP_STATE_DIR ?? path.resolve(HERE, '..', 'state')

const PORT = Number(process.env.OTP_PORT ?? 8790)
const PRIVATE_IP = process.env.OTP_PRIVATE_IP ?? '127.0.0.1'
const phoneToken = process.env.OTP_PHONE_TOKEN
const pcToken = process.env.OTP_PC_TOKEN

if (!phoneToken || !pcToken) {
  console.error('FATAL: OTP_PHONE_TOKEN and OTP_PC_TOKEN must both be set')
  process.exit(1)
}
if (phoneToken === pcToken) {
  console.error('FATAL: the two tokens must differ — scope separation is the point')
  process.exit(1)
}

const store = createStore({ maxEntries: 20, ttlMs: 5 * 60 * 1000 })
const rateLimiter = createRateLimiter({ limit: 30, windowMs: 60000 })
const handler = createHandler({
  store, phoneToken, pcToken, rateLimiter,
  updateDir: UPDATE_DIR,
  stateDir: STATE_DIR,
})

setInterval(() => {
  const n = store.sweep(Date.now())
  if (n) console.log(`swept ${n} expired entr${n === 1 ? 'y' : 'ies'}`)
}, 30000).unref()

// Two listeners, one handler. Never 0.0.0.0: loopback is the reverse-proxy ingress
// for the phone, the private-network address is how the PC agent reads. Nothing is
// exposed on the host's public IP. Set OTP_PRIVATE_IP to your VPN / LAN address.
for (const host of [...new Set(['127.0.0.1', PRIVATE_IP])]) {
  const server = http.createServer(handler)
  server.on('error', err => {
    console.error(`FATAL: cannot bind ${host}:${PORT} — ${err.message}`)
    process.exit(1)
  })
  server.listen(PORT, host, () => console.log(`otp-relay listening on ${host}:${PORT}`))
}

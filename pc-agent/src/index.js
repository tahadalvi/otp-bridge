import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { writeLatest } from './writer.js'
import { connect } from './sse.js'

// State lives beside the repo rather than in %LOCALAPPDATA% so that the path is
// identical for every process that touches it. Override with OTP_CONFIG.
const HERE = path.dirname(fileURLToPath(import.meta.url))
const DEFAULT_STATE_DIR = path.resolve(HERE, '..', '..', '..', 'otp-bridge-state')

const CONFIG_PATH = process.env.OTP_CONFIG ?? path.join(DEFAULT_STATE_DIR, 'config.json')

const log = (...a) => console.log(new Date().toISOString(), ...a)

async function loadConfig() {
  let raw
  try {
    raw = await fs.readFile(CONFIG_PATH, 'utf8')
  } catch {
    console.error(`FATAL: no config at ${CONFIG_PATH}`)
    console.error('Copy pc-agent/config.example.json there and fill in pcToken,')
    console.error('or point OTP_CONFIG at an existing config file.')
    process.exit(1)
  }
  const cfg = JSON.parse(raw)
  for (const key of ['streamUrl', 'ackUrlTemplate', 'pcToken', 'targetPath']) {
    if (!cfg[key]) { console.error(`FATAL: config is missing "${key}"`); process.exit(1) }
  }
  return cfg
}

const cfg = await loadConfig()
const ac = new AbortController()
process.on('SIGINT', () => { log('shutting down'); ac.abort() })
process.on('SIGTERM', () => ac.abort())

log(`watching ${cfg.streamUrl} -> ${cfg.targetPath}`)

await connect({
  url: cfg.streamUrl,
  token: cfg.pcToken,
  signal: ac.signal,
  onStatus: s => log('status:', JSON.stringify(s)),
  onEvent: async e => {
    let entry
    try {
      entry = JSON.parse(e.data)
    } catch (err) {
      log('skipping unparseable event:', err.message)
      return
    }

    try {
      const { atomic } = await writeLatest(cfg.targetPath, entry, new Date().toISOString())
      log(`wrote ${entry.code} from ${entry.sender}`)
      if (!atomic) {
        // Something held latest.json open, so the swap fell back to an in-place
        // write. Delivered either way, but a reader could momentarily have seen
        // a torn document — worth knowing if it happens often.
        log('note: written in place, not swapped — a reader was holding the file open')
      }
    } catch (err) {
      // Do not ack — leave it pending so a later reconnect retries the write.
      log('write failed, leaving unacked:', err.message)
      return
    }

    try {
      const res = await fetch(cfg.ackUrlTemplate.replace('{id}', encodeURIComponent(entry.id)), {
        method: 'POST',
        headers: { authorization: `Bearer ${cfg.pcToken}` },
      })
      if (!res.ok) log(`ack returned ${res.status}`)
    } catch (err) {
      // Harmless: the relay's 5-minute TTL will clear it.
      log('ack failed:', err.message)
    }
  },
})

export function parseFrames(buffer) {
  const events = []
  let rest = buffer

  while (true) {
    const idx = rest.indexOf('\n\n')
    if (idx === -1) break
    const raw = rest.slice(0, idx)
    rest = rest.slice(idx + 2)

    let event = 'message'
    const dataLines = []
    for (const line of raw.split('\n')) {
      if (line.startsWith(':')) continue                      // comment / keep-alive
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim())
    }
    if (dataLines.length) events.push({ event, data: dataLines.join('\n') })
  }

  return { events, rest }
}

export function backoffMs(attempt) {
  return Math.min(1000 * 2 ** attempt, 30000)
}

export async function connect({ url, token, onEvent, onStatus, signal, backoff = backoffMs }) {
  let attempt = 0

  while (!signal.aborted) {
    try {
      const res = await fetch(url, {
        headers: { authorization: `Bearer ${token}` },
        signal,
      })
      if (!res.ok) throw new Error(`relay returned ${res.status}`)

      attempt = 0
      onStatus({ state: 'connected' })

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buf = ''

      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })
        const { events, rest } = parseFrames(buf)
        buf = rest
        for (const e of events) if (e.event === 'otp') await onEvent(e)
      }
      onStatus({ state: 'disconnected', reason: 'stream ended' })
    } catch (err) {
      if (signal.aborted) break
      onStatus({ state: 'error', reason: err.message })
    }

    if (signal.aborted) break
    const wait = backoff(attempt++)
    onStatus({ state: 'reconnecting', inMs: wait })
    await new Promise(r => setTimeout(r, wait))
  }
}

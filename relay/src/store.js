// In-memory only. Codes must never touch disk on the relay host.
export function createStore({ maxEntries = 20, ttlMs = 300000 } = {}) {
  const entries = new Map() // id -> entry, insertion-ordered

  return {
    add(entry) {
      if (entries.has(entry.id)) return false
      entries.set(entry.id, entry)
      while (entries.size > maxEntries) {
        entries.delete(entries.keys().next().value)
      }
      return true
    },

    pending() {
      return [...entries.values()]
    },

    ack(id) {
      return entries.delete(id)
    },

    sweep(nowMs) {
      let removed = 0
      for (const [id, e] of entries) {
        if (nowMs - e.storedAt > ttlMs) { entries.delete(id); removed++ }
      }
      return removed
    },

    size() {
      return entries.size
    },
  }
}

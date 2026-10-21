# OTP Bridge — Design

**Date:** 2026-07-30
**Status:** Approved for planning

Forward one-time passcodes from an Android phone to a JSON file on a Windows PC, relayed
through a small relay on a host you control.

---

## 1. Goal and success criteria

An SMS OTP arriving on the phone from an allowlisted sender appears at
`C:\otp-bridge\latest.json` on the PC within a few seconds, with no manual step.

Success:

- A code from an allowlisted sender lands in the file, correctly parsed, within ~3s of the SMS arriving.
- Delivery survives the phone being briefly offline (retried, not dropped).
- A reader of the file never observes a partially written document.
- Nothing but allowlisted senders' messages ever leaves the phone.
- The APK installs by sideload; no Play Store involvement.

Explicit non-goals:

- No iOS support.
- No auto-typing or clipboard injection on the PC. The file is the contract; anything
  consuming it is out of scope.
- No OTP history or search. The relay is a courier, not a store, and the PC keeps only the
  latest code. (The app's on-device activity list is diagnostics — see §3 — and holds no codes.)

---

## 2. Architecture

```
phone (APK)                    relay host                  PC
    |                                 |                            |
    |  POST /otp-bridge/otp           |                            |
    |  Bearer PHONE_TOKEN             |                            |
    |  public HTTPS :443              |                            |
    |-------------------------------->|                            |
    |        (Tailscale Funnel)       |                            |
    |                                 |   GET /otp/stream (SSE)    |
    |                                 |   Bearer PC_TOKEN          |
    |                                 |<---------------------------|
    |                                 |   private network 10.0.0.2    |
    |                                 |                            |
    |                                 |   POST /otp/:id/ack        |
    |                                 |<---------------------------|
    |                                 |                     atomic write
    |                                 |            C:\otp-bridge\latest.json
```

Two directions, two trust levels. The phone writes over the public door and can never read.
The PC reads over the private private network and can never be reached from the internet.

### Repository layout

```
<repo>\
  android\      Kotlin + Compose app  -> app-release.apk
  relay\        Node service, deployed to relay-host:~/otp-relay
  pc-agent\     Node script, runs at logon
  docs\superpowers\specs\
```

---

## 3. Android app

Kotlin, Jetpack Compose, `minSdk 26`, `targetSdk 36`, package `dev.taha.otpbridge`.
Built against SDK at `%LOCALAPPDATA%\Android\Sdk` (build-tools 36.1.0, platform android-36)
using the JDK bundled with Android Studio at `C:\Program Files\Android\Android Studio\jbr`
— the `java` on `PATH` is 11 and is too old for current Gradle.

### Permissions

| Permission | Why |
|---|---|
| `RECEIVE_SMS` | Fire on message arrival |
| `READ_SMS` | Populate the recent-senders picker from the inbox |
| `INTERNET` | POST to the relay |
| `POST_NOTIFICATIONS` | Surface delivery failures on Android 13+ |

The app does **not** need to be the default SMS handler. The `SMS_RECEIVED_ACTION` broadcast
is delivered to any app holding `RECEIVE_SMS`.

### Delivery path

1. `SmsReceiver : BroadcastReceiver` fires on `SMS_RECEIVED_ACTION`.
2. Sender is compared against the allowlist. Non-matches are recorded in the local activity
   log (sender only, body discarded) and dropped.
3. The code is extracted from the body.
4. A `ForwardWorker` is enqueued via **WorkManager** with a `CONNECTED` network constraint
   and exponential backoff. This is what makes an OTP arriving inside a lift survive: the
   POST retries rather than vanishing. Work is deduplicated by message id.

No foreground service. A receiver plus WorkManager covers the requirement without a
persistent notification or battery cost.

### Extraction rule

Scan the body for standalone runs of 4–8 digits (`\b\d{4,8}\b`). If more than one matches,
prefer the run nearest a keyword from `otp, code, verification, one-time, otp code, pin`.
Otherwise take the first. Deliberately simple — the allowlist has already removed the
messages that would need cleverness.

### UI — single Compose screen

- **Status** — relay reachability, result and timestamp of the last forward.
- **Allowlist** — senders with swipe-to-delete. **Add** opens a picker of distinct senders
  drawn from the recent inbox, so adding is a tap rather than typing `VM-YOURBANK` from memory.
- **Recent activity** — last 20 events, including *ignored* ones. This is the mitigation for
  the allowlist's central weakness: a code from a new service that silently did not arrive is
  visible here and addable in one tap.
  Each entry stores **sender, timestamp and outcome only** — never the message body, never the
  code. Nothing sensitive is retained on the device by this app.
- **Settings** — relay base URL, device token, **Test send**, and a button opening
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

State persisted in DataStore (Preferences).

---

## 4. Relay on the relay host

Single-file Node service, no npm dependencies (`node:http`, `node:crypto` only), deployed to
`~/otp-relay` with its own systemd unit `otp-relay.service`.

> **Building separation.** The relay is its own service: its own directory, its own unit,
> and it imports nothing from anything else on the host.

### Listeners

The same request handler is bound twice:

- `127.0.0.1:8790` — ingress from Tailscale Funnel.
- `10.0.0.2:8790` — the private network address, for the PC agent.

It is never bound to `0.0.0.0`, so nothing is exposed on the host's public IP. Port 8790 is
currently free on the box.

### Public exposure

```
tailscale serve --bg --set-path /otp-bridge http://127.0.0.1:8790
```

chosen over Funnel's alternative port 10000 because restrictive networks — hotel Wi-Fi,
corporate guest, some mobile APNs — block outbound non-standard ports, which is precisely the
away-from-home case this exists for.

Funnel traffic reaches `tailscaled` over Tailscale's own relay, not through inbound OCI
networking, so no OCI security-list change is required.

The handler strips an optional leading `/otp-bridge` from the request path before routing, so
it behaves identically whether or not `tailscale serve` strips the prefix itself.

### Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/otp` | `PHONE_TOKEN` | Accept `{id, code, sender, body, receivedAt}` |
| `GET` | `/otp/stream` | `PC_TOKEN` | SSE stream of pending entries |
| `POST` | `/otp/:id/ack` | `PC_TOKEN` | Drop an entry once written to disk |
| `GET` | `/healthz` | none | Liveness |

### Storage

**In memory only. Codes are never written to disk on the relay host.** Bounded ring of 20 entries, swept
on a 5-minute TTL, deleted on ack. Ack rather than delete-on-push means a PC restart mid-flight
does not lose the code.

### Auth

Two distinct bearer tokens. The phone's is write-only; it cannot open the stream or read
history. Compared using `crypto.timingSafeEqual`. Per-token rate limit of 30 requests/minute.

---

## 5. PC agent

Single Node file, no dependencies. Holds an SSE connection to
`http://10.0.0.2:8790/otp/stream` over the private network, reconnecting with exponential backoff
capped at 30s.

On each event it writes to `C:\otp-bridge\latest.json.tmp` then renames over the target.
The rename is atomic on NTFS, so a script polling the file can never catch a half-written
document. It acks only after the rename returns.

```json
{
  "code": "483920",
  "sender": "VM-YOURBANK",
  "receivedAt": "2026-07-30T14:30:22+05:30",
  "deliveredAt": "2026-07-30T14:30:23+05:30",
  "body": "483920 is your OTP. Do not share."
}
```

Registered with Task Scheduler to run at logon, hidden, restarting on failure.

---

## 6. Secrets

Two 32-byte random tokens, generated once at setup.

| Where | Location | Protection |
|---|---|---|
| relay host | `~/otp-relay/.env` | mode 600 |
| PC | `%LOCALAPPDATA%\otp-bridge\config.json` | user profile ACL |
| Phone | DataStore | app-private storage |

No token is ever written inside `<repo>\`. The repo carries a
`config.example.json` with placeholders only.

---

## 7. Testing

| Layer | Approach |
|---|---|
| Extractor | JVM unit test over a corpus of real OTP message shapes. No device needed, so most correctness is pinned here. |
| Allowlist matching | JVM unit test: exact match, case sensitivity, unknown sender rejection. |
| Relay | Auth rejection (wrong token, phone token on stream endpoint), TTL expiry, ack deletion, rate limit, path-prefix stripping. |
| PC agent | Atomic write under a concurrent reader; reconnect against a fake SSE server that drops mid-stream. |
| Leg isolation | `curl` directly at `POST /otp` exercises relay host→PC without involving the phone, so a failure localises to one half immediately. |
| End-to-end | Sideload, add a sender, trigger a real OTP, confirm the file. |

---

## 8. Known limits

1. **OEM battery management.** Xiaomi, Samsung and OnePlus kill background receivers
   aggressively. The battery-optimization exemption helps but is not a guarantee; some
   per-device tuning should be expected.
2. **RCS is not covered.** Google Messages RCS conversations do not fire `SMS_RECEIVED`.
   Bank and service OTPs are effectively always plain SMS, so this is unlikely to bite, but
   it is a real boundary.
3. **Injection.** Anyone holding the phone token can push a fabricated code into the file.
   Rate limiting blunts this; it does not remove it. Relevant if the file is ever wired into
   something that acts without a human.
4. **Allowlist blind spot.** A new service's first OTP will not arrive. The recent-activity
   list showing ignored senders is the designed mitigation, but it is a recovery path, not
   prevention.
5. **PC must be running.** Codes expire from the relay after 5 minutes. An OTP sent while the
   PC is asleep is gone.

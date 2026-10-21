# OTP Bridge

Forwards SMS one-time passcodes from an Android phone to a JSON file on your PC, within
seconds, with no one touching the phone. Built so that end-to-end tests of OTP-gated sign-in
flows can run unattended.

```
phone (APK)  ──POST /otp-bridge/otp──►  relay  ──SSE──►  PC agent  ──►  latest.json
             public HTTPS, write-only       private network, read-only   atomic swap
```

Three small, independently deployable parts:

| Part | What | Stack |
|---|---|---|
| `android/` | Reads the OTP SMS and posts it to the relay | Kotlin, Jetpack Compose, WorkManager |
| `relay/` | Courier with two scoped tokens; holds codes in memory for at most five minutes | Node.js, zero dependencies |
| `pc-agent/` | Holds a live stream to the relay, writes `latest.json` atomically, acks | Node.js, zero dependencies |

The design doc is in [`docs/superpowers/specs/`](docs/superpowers/specs/2026-07-30-otp-bridge-design.md).

## What your app sees

```json
{
  "id": "9f1c4e2a-3b7d-4c8f-a1e6-5d0b2c9f8a37",
  "code": "483920",
  "sender": "VM-YOURBANK",
  "body": "483920 is your OTP. Do not share.",
  "receivedAt": "2026-07-31T12:00:00.000+05:30",
  "deliveredAt": "2026-07-31T06:30:03.312Z"
}
```

A Playwright test consumes it in two lines:

```python
otp = json.load(open("C:/otp-bridge/latest.json"))["code"]
page.fill("#otp", otp)
```

**Read the file and close it, then wait.** A script that reads in an unbroken loop keeps a
Windows file handle open permanently, and no rename can land underneath it. See the note in
`pc-agent/src/writer.js`.

## Security model

- **Two tokens, two directions.** The phone holds a *write-only* token and can only `POST /otp`.
  The PC holds a *read-only* token and can only read the stream and ack. Neither side can do
  the other's job, and the tokens are checked in constant time.
- **Nothing on disk at the relay.** Codes live in memory in a bounded ring (20 entries), expire
  after five minutes, and are deleted on ack.
- **No secret in the APK.** The build embeds a *pairing* token, minted by the relay with a use
  cap and a 7-day expiry. On first launch the app trades it for the real phone token via
  `POST /pair/claim`; the relay decrements server-side, so a spent APK carries nothing usable.
- **Never bound to `0.0.0.0`.** The relay listens on loopback (for the HTTPS ingress) and on the
  host's private-network address (for the PC agent). Nothing is exposed on the host's public IP.
- **Updates are pinned to the signing key.** The app can fetch new builds from the relay, but
  Android refuses an in-place update signed with a different key, and the app verifies the
  SHA-256 the relay advertised before handing the file to the installer.
- **Rate limited.** 30 posts per minute per token, sliding window.

## Tests

| Part | Command | Count |
|---|---|---|
| Relay | `cd relay && node --test` | 57 |
| PC agent | `cd pc-agent && node --test` | 15 |
| Android | `cd android && ./gradlew :app:testDebugUnitTest` | 25 |

The Node parts use the built-in `node:test` runner. No Jest, no Mocha, no `npm install`.

## Deploying the relay

On any Linux host you control (a small VPS is plenty):

```sh
scp -r relay/src relay/deploy relay/package.json relay-host:~/otp-relay/
ssh relay-host "~/otp-relay/deploy/install.sh"
```

The installer generates both tokens on first run and prints them **once**, installs a hardened
systemd unit (`ProtectSystem=strict`, `ProtectHome=read-only`, `NoNewPrivileges`), and
optionally publishes the `/otp-bridge` path over HTTPS with Tailscale Funnel. Any reverse proxy
that terminates TLS and forwards `/otp-bridge` to `127.0.0.1:8790` works the same way.

Set `OTP_PRIVATE_IP` in `~/otp-relay/.env` to the host's VPN or LAN address so the PC agent can
reach the stream without going through the public door. See `relay/config.example.env`.

## Running the PC agent

```powershell
# state lives in a sibling folder, outside the repo and outside git
mkdir ..\otp-bridge-state
copy pc-agent\config.example.json ..\otp-bridge-state\config.json
# fill in streamUrl, ackUrlTemplate, pcToken and targetPath, then:
cd pc-agent && node src\index.js
```

`pc-agent/install-task.ps1` registers it as a hidden, logon-triggered Task Scheduler job that
restarts on failure and logs to `otp-bridge-state\agent.log`.

## Building the Android app

Gradle needs JDK 17+ (set `JAVA_HOME` to Android Studio's bundled JBR if your `java` is older).

```powershell
.\scripts\build-apk.ps1 -Uses 5   # mints a 5-phone pairing token and builds a signed APK
```

Copy `dist/otp-bridge.apk` to the phone and open it. Nothing to type: the app pairs itself on
first launch, then you add the senders you want forwarded. Later builds ship over the air with
`.\scripts\publish-update.ps1`, or over adb with `.\scripts\push-update.ps1`.

Release signing reads `android/keystore.properties`, which is gitignored. Keep the keystore and
its password outside the repo; losing them means a future build cannot upgrade an installed app
in place.

## Known limits

1. **OEM battery management.** Xiaomi, Samsung and OnePlus kill background receivers; the app
   has a one-tap exemption button.
2. **RCS is not covered.** Google Messages RCS chats do not fire `SMS_RECEIVED`. Bank and
   service OTPs are, in practice, always plain SMS.
3. **Injection.** Anyone holding the phone token can push a fabricated code into the file. Rate
   limiting blunts this; it does not remove it.
4. **A new sender's first OTP will not arrive.** It shows as `ignored` in Recent activity with
   a one-tap **Allow**.
5. **The PC must be running.** Codes expire from the relay after five minutes. A queued code
   *is* replayed when the agent reconnects, so a brief restart loses nothing.

## Secrets

None are in this repo. `.gitignore` blocks `.env`, `config.json`, `keystore.properties`,
`*.jks`, `*.keystore` and `*.apk`. Example files carry placeholders only.

## Need something like this built?

I build small, reliable automation like this as fixed-price work: scrapers, bots, pipelines and
test tooling, with the source code handed over.
[Hire me on Fiverr](https://www.fiverr.com/tahadalvi11).

## Licence

MIT

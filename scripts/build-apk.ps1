# Builds a signed APK that configures itself on first launch.
#
# Mints a pairing token from the relay and compiles it into the APK. On first
# launch the app trades it for the real phone token; the relay decrements the
# use count. So one APK installs on several phones, and once the cap is spent
# or the TTL passes, the APK carries nothing an attacker can use.
#
#   .\scripts\build-apk.ps1              # 5 pairings
#   .\scripts\build-apk.ps1 -Uses 10     # 10 pairings
param(
  [int]$Uses = 5,
  [string]$RelayAdmin = 'http://10.0.0.2:8790',
  [string]$RelayPublic = 'https://relay.example.com/otp-bridge'
)
$ErrorActionPreference = 'Stop'

$repo     = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$stateDir = Join-Path (Split-Path -Parent $repo) 'otp-bridge-state'
$cfgPath  = Join-Path $stateDir 'config.json'
if (-not (Test-Path $cfgPath)) { throw "No config.json at $cfgPath" }

$pcToken = (Get-Content $cfgPath -Raw | ConvertFrom-Json).pcToken
if (-not $pcToken) { throw 'config.json has no pcToken' }

Write-Host "Minting a pairing token good for $Uses phone(s)..."
$resp = Invoke-RestMethod -Method Post -Uri "$RelayAdmin/pair/mint" `
  -Headers @{ authorization = "Bearer $pcToken" } `
  -ContentType 'application/json' -Body (@{ uses = $Uses } | ConvertTo-Json) -TimeoutSec 20

Write-Host ("  token minted, uses={0}, ttl={1:N0} days" -f $resp.uses, ($resp.expiresInMs / 86400000))

# Consumed by app/build.gradle.kts at configuration time. Lives in the state
# dir, not the repo, because it is a credential.
$pairProps = Join-Path $stateDir 'pair.properties'
@"
pairToken=$($resp.pairToken)
relayUrl=$RelayPublic
"@ | Set-Content -Path $pairProps -Encoding ascii

Push-Location (Join-Path $repo 'android')
try {
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
  & .\gradlew.bat :app:assembleRelease
  if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }

$apk  = Join-Path $repo 'android\app\build\outputs\apk\release\app-release.apk'
$dist = Join-Path $repo 'dist'
New-Item -ItemType Directory -Force $dist | Out-Null
Copy-Item $apk (Join-Path $dist 'otp-bridge.apk') -Force

Write-Host ""
Write-Host "APK: $(Join-Path $dist 'otp-bridge.apk')"
Write-Host "Install it on up to $($resp.uses) phone(s). Each configures itself on first launch."
Write-Host "Re-run this script to mint a fresh token when those are used up."

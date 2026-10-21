# Ships a new build to every phone, over the network, with no cable and no adb.
#
#   .\scripts\publish-update.ps1
#   .\scripts\publish-update.ps1 -VersionName 1.2
#
# Bumps versionCode, builds a signed APK, and uploads it to the relay. Phones
# pick it up on their next check (every 6 hours, on app launch, or immediately
# via "Check for updates" in the app).
#
# Android refuses an in-place update signed with a different key, so this
# channel can only ever deliver builds signed with the project keystore.
# Pass -MintPairing when the build is also going to a *new* phone: it embeds a
# fresh pairing token so that phone can configure itself. Existing phones ignore
# it — they already hold the real token.
param(
  [string]$VersionName,
  [switch]$MintPairing,
  [int]$Uses = 5,
  [string]$RelayAdmin = 'http://10.0.0.2:8790',
  [string]$RelayPublic = 'https://relay.example.com/otp-bridge'
)
$ErrorActionPreference = 'Stop'

$repo     = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$stateDir = Join-Path (Split-Path -Parent $repo) 'otp-bridge-state'
$verFile  = Join-Path $repo 'android\version.properties'

$pcToken = (Get-Content (Join-Path $stateDir 'config.json') -Raw | ConvertFrom-Json).pcToken
if (-not $pcToken) { throw 'No pcToken in otp-bridge-state\config.json' }

# --- bump the build number -------------------------------------------------
$lines = Get-Content $verFile
$code  = [int](($lines | Where-Object { $_ -match '^versionCode=' }) -replace 'versionCode=','')
$name  = (($lines | Where-Object { $_ -match '^versionName=' }) -replace 'versionName=','')
$code++
if ($VersionName) { $name = $VersionName }

$lines = $lines |
  ForEach-Object {
    if ($_ -match '^versionCode=') { "versionCode=$code" }
    elseif ($_ -match '^versionName=') { "versionName=$name" }
    else { $_ }
  }
Set-Content -Path $verFile -Value $lines -Encoding ascii
if ($MintPairing) {
  Write-Host "Minting a pairing token good for $Uses phone(s)..."
  $pair = Invoke-RestMethod -Method Post -Uri "$RelayAdmin/pair/mint" `
    -Headers @{ authorization = "Bearer $pcToken" } `
    -ContentType 'application/json' -Body (@{ uses = $Uses } | ConvertTo-Json) -TimeoutSec 20
  @"
pairToken=$($pair.pairToken)
relayUrl=$RelayPublic
"@ | Set-Content -Path (Join-Path $stateDir 'pair.properties') -Encoding ascii
  Write-Host "  uses=$($pair.uses)"
}

Write-Host "Building $name (build $code)..."

# --- build -----------------------------------------------------------------
# No fresh pairing mint: phones that are already paired hold the real token, and
# an update preserves app data, so a new pairing token would be wasted.
Push-Location (Join-Path $repo 'android')
try {
  $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
  & .\gradlew.bat :app:assembleRelease
  if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
} finally { Pop-Location }

$apk = Join-Path $repo 'android\app\build\outputs\apk\release\app-release.apk'
Copy-Item $apk (Join-Path $repo 'dist\otp-bridge.apk') -Force

# --- publish ---------------------------------------------------------------
Write-Host "Uploading $([math]::Round((Get-Item $apk).Length / 1MB, 1)) MB to the relay..."
$meta = Invoke-RestMethod -Method Put `
  -Uri "$RelayAdmin/update/apk?versionCode=$code&versionName=$name" `
  -Headers @{ authorization = "Bearer $pcToken" } `
  -InFile $apk -ContentType 'application/octet-stream' -TimeoutSec 180

Write-Host ""
Write-Host "Published $($meta.versionName) (build $($meta.versionCode))"
Write-Host "  size:   $($meta.size) bytes"
Write-Host "  sha256: $($meta.sha256)"
Write-Host ""
Write-Host 'Phones will offer it on next check. To pull it immediately, open the app and tap "Check for updates".'

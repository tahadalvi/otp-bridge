# Builds the current source and pushes it straight onto the phone over Wi-Fi.
#
# One-time setup on the phone:
#   Settings > Developer options > Wireless debugging > on
#   "Pair device with pairing code" shows  IP:PORT  and a 6-digit code
#   Then run, once:
#     .\scripts\push-update.ps1 -PairAddress 192.168.1.x:PORT -PairCode 123456 -DeviceAddress 192.168.1.x:PORT2
#   (PORT2 is the port on the main "Wireless debugging" screen, NOT the pairing port.)
#
# After that, every update is just:
#   .\scripts\push-update.ps1
#
# Installs with -r, so app data survives: the phone keeps the token it already
# paired with and does not spend another pairing.
param(
  [string]$PairAddress,
  [string]$PairCode,
  [string]$DeviceAddress,
  [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'

$repo     = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$stateDir = Join-Path (Split-Path -Parent $repo) 'otp-bridge-state'
$adb      = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$remember = Join-Path $stateDir 'adb-device.txt'
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }

if ($PairAddress) {
  if (-not $PairCode) { throw 'Pairing needs -PairCode as well.' }
  Write-Host "Pairing with $PairAddress ..."
  $PairCode | & $adb pair $PairAddress
  if ($LASTEXITCODE -ne 0) { throw 'Pairing failed. Check the address and code are the ones on screen right now — they rotate.' }
}

# Pairing via Android Studio's QR flow registers the device under an mDNS
# service name, not IP:PORT, so prefer whatever adb already has attached.
$attached = @(& $adb devices | Select-Object -Skip 1 |
  Where-Object { $_ -match '\sdevice$' } |
  ForEach-Object { ($_ -split '\s+')[0] })

if (-not $DeviceAddress) {
  if ($attached.Count -eq 1) {
    $DeviceAddress = $attached[0]
    Write-Host "Using attached device $DeviceAddress"
  } elseif ($attached.Count -gt 1) {
    Write-Host "Several devices attached — pushing to all of them:"
    $attached | ForEach-Object { "  $_" } | Write-Host
  } elseif (Test-Path $remember) {
    $DeviceAddress = (Get-Content $remember -Raw).Trim()
    Write-Host "Nothing attached; trying remembered device $DeviceAddress"
    & $adb connect $DeviceAddress | Out-Host
  } else {
    throw 'No device attached. Pair first (Android Studio > Device Manager > Pair Devices Using Wi-Fi), or pass -DeviceAddress IP:PORT.'
  }
}

$targets = if ($attached.Count -gt 1) { $attached } else { @($DeviceAddress) }

foreach ($t in $targets) {
  $state = (& $adb -s $t get-state) 2>&1
  if ($state -notmatch 'device') {
    throw "Phone $t not reachable (state: $state). Wireless debugging turns itself off on reboot and the port changes — re-check Developer options."
  }
}
if ($targets.Count -eq 1) { Set-Content -Path $remember -Value $targets[0] -Encoding ascii }

if (-not $SkipBuild) {
  Push-Location (Join-Path $repo 'android')
  try {
    $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
    # Deliberately no fresh pairing mint: the phone already holds the real token,
    # and -r keeps its data, so a new pairing token would just be wasted.
    & .\gradlew.bat :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
  } finally { Pop-Location }
  Copy-Item (Join-Path $repo 'android\app\build\outputs\apk\release\app-release.apk') `
            (Join-Path $repo 'dist\otp-bridge.apk') -Force
}

$apk = Join-Path $repo 'dist\otp-bridge.apk'
$mb  = [math]::Round((Get-Item $apk).Length / 1MB, 1)

foreach ($t in $targets) {
  Write-Host "Installing $mb MB onto $t ..."
  & $adb -s $t install -r $apk | Out-Host
  if ($LASTEXITCODE -ne 0) { throw "Install failed on $t." }
  $ver = (& $adb -s $t shell dumpsys package dev.taha.otpbridge | Select-String 'versionName' | Select-Object -First 1).ToString().Trim()
  Write-Host "  done — $ver"
}
Write-Host ""
Write-Host "Updated $($targets.Count) device(s). App data preserved, no pairing spent."

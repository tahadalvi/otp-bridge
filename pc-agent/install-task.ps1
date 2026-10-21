# Registers the OTP Bridge agent to run at logon, hidden, restarting on failure.
#
# Two things this works around, both discovered the hard way:
#
#  1. The task executes a generated .cmd wrapper rather than node.exe directly.
#     Task Scheduler's argument quoting does not survive paths containing spaces
#     (for example a folder with a space in its name). The wrapper also gives the
#     otherwise-hidden agent a log file.
#
#  2. State lives beside the repo on the real filesystem, NOT in %LOCALAPPDATA%.
#     An agent session writing to %LOCALAPPDATA% may be writing into a sandbox
#     overlay that Task Scheduler cannot see, so the task fails with 0x1 and
#     produces no output at all. Keeping everything under E: avoids that whole
#     class of confusion.
$ErrorActionPreference = 'Stop'

$agentDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$entry    = Join-Path $agentDir 'src\index.js'
$node     = (Get-Command node).Source
$taskName = 'OtpBridgeAgent'
$stateDir = Resolve-Path (Join-Path $agentDir '..\..\otp-bridge-state') -ErrorAction SilentlyContinue
if (-not $stateDir) { throw "State directory not found. Expected a sibling 'otp-bridge-state' next to the repo." }
$stateDir = $stateDir.Path
$wrapper  = Join-Path $stateDir 'run-agent.cmd'
$logFile  = Join-Path $stateDir 'agent.log'

if (-not (Test-Path $entry)) { throw "Cannot find $entry" }
if (-not (Test-Path (Join-Path $stateDir 'config.json'))) { throw "No config.json in $stateDir" }

# Machine-specific paths, so this is generated into the state dir, not the repo.
$wrapperBody = @"
@echo off
cd /d "$agentDir"
"$node" "$entry" >> "$logFile" 2>&1
"@
Set-Content -Path $wrapper -Value $wrapperBody -Encoding ascii

$action = New-ScheduledTaskAction -Execute $wrapper -WorkingDirectory $agentDir
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
  -StartWhenAvailable -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
  -ExecutionTimeLimit ([TimeSpan]::Zero) -Hidden
$principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited

if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
  Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
}
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger `
  -Settings $settings -Principal $principal -Description 'Writes relayed SMS OTPs to latest.json' | Out-Null

Start-ScheduledTask -TaskName $taskName
Start-Sleep -Seconds 6

$info = Get-ScheduledTaskInfo -TaskName $taskName
"TaskName:       $taskName"
"State:          $((Get-ScheduledTask -TaskName $taskName).State)"
"LastTaskResult: 0x{0:X}" -f $info.LastTaskResult
"Log:            $logFile"
if (Test-Path $logFile) { "--- last log lines ---"; Get-Content $logFile -Tail 5 }

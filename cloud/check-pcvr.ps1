# Vice City Bodycam — PCVR host readiness check
# Run in PowerShell on a Windows cloud gaming host. Does not purchase hardware,
# install games, modify DRM, or claim that a remote PSVR1 is an OpenXR headset.
$ErrorActionPreference = 'Continue'
Write-Host "Vice City Bodycam / PCVR readiness" -ForegroundColor Cyan
$checks = [ordered]@{}
$checks['Windows'] = [Environment]::OSVersion.Platform -eq 'Win32NT'
try {
  $gpu = @(Get-CimInstance Win32_VideoController | Select-Object -ExpandProperty Name)
  $checks['GPU'] = ($gpu -join ', ')
} catch { $checks['GPU'] = 'Unknown' }
$steam = Join-Path ${env:ProgramFiles(x86)} 'Steam'
$checks['Steam'] = Test-Path (Join-Path $steam 'steam.exe')
$checks['SteamVR'] = Test-Path (Join-Path $steam 'steamapps/common/SteamVR/bin/win64/vrserver.exe')
$gtapaths = @(
  (Join-Path $steam 'steamapps/common/Grand Theft Auto V Enhanced/GTA5_Enhanced.exe'),
  (Join-Path $steam 'steamapps/common/Grand Theft Auto V Enhanced/GTA5.exe')
)
$checks['GTA V Enhanced'] = @($gtapaths | Where-Object { Test-Path $_ }).Count -gt 0
$checks['Sunshine'] = (Test-Path "$env:ProgramFiles\Sunshine\sunshine.exe") -or
  (Test-Path "$env:ProgramFiles\Sunshine\tools\sunshine.exe")
$checks['PSVR1 OpenXR bridge'] = $false
$checks['Stereo VR runtime'] = 'Requires compatible licensed R.E.A.L. VR Enhanced build'
$checks['Cloud-to-headset tracking'] = 'Not implemented / not verified'
foreach ($k in $checks.Keys) { Write-Host ("{0}: {1}" -f $k, $checks[$k]) }
Write-Host 'STOP: Do not start a paid cloud session for VR until an OpenXR headset bridge, tracking return path, and measured network latency are verified.'

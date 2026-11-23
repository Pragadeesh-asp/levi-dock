# LEVI DOCK - start remote control of the Mi 3W (LineageOS 18.1 / Android 11)
# One scrcpy window: video + laptop mouse/keyboard + phone audio.
# Uses USB when the cable is connected, otherwise Wi-Fi ADB.
# (The old MIUI two-window OTG workaround is no longer needed.)

param([switch]$Wifi)

$ErrorActionPreference = 'Continue'
$adb    = 'C:\platform-tools\adb.exe'
$scrcpy = 'C:\scrcpy\scrcpy.exe'
$serial = 'b17bad2'
$port   = 5555

Get-Process scrcpy -ErrorAction SilentlyContinue | Stop-Process -Force
& $adb start-server | Out-Null

$usb = (& $adb devices) -match "^$serial\s+device"
$target = $serial

if ($Wifi -or -not $usb) {
    if ($usb) {
        $m = (& $adb -s $serial shell "ip -f inet addr show wlan0") | Select-String 'inet (\d+\.\d+\.\d+\.\d+)'
        $ip = if ($m) { $m.Matches[0].Groups[1].Value } else { '192.168.1.11' }
        & $adb -s $serial tcpip $port | Out-Null
        Start-Sleep 5
    } else { $ip = '192.168.1.11' }
    & $adb connect "${ip}:$port" | Out-Null
    $target = "${ip}:$port"
}

# Keep the screen on while charging (allowed on LineageOS).
& $adb -s $target shell settings put global stay_on_while_plugged_in 7 | Out-Null

# Start the LEVI DOCK host (streams laptop metrics to the dock app) if it isn't running.
$hostExe = Join-Path $PSScriptRoot 'host\target\release\levi-host.exe'
if ((Test-Path $hostExe) -and -not (Get-Process levi-host -ErrorAction SilentlyContinue)) {
    $env:LEVI_SERIAL = $target
    Start-Process $hostExe -WindowStyle Minimized
    Write-Host "Started levi-host"
}

Write-Host "Connecting to $target"
Start-Process $scrcpy -ArgumentList "--serial $target --max-size 1280 --video-bit-rate 6M --max-fps 60 --audio-codec=aac --window-title `"LEVI DOCK - Mi 3W`"" `
    -RedirectStandardOutput "$env:TEMP\levi_scrcpy_out.txt" -RedirectStandardError "$env:TEMP\levi_scrcpy_err.txt"

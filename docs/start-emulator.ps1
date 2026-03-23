$SDK = "$env:LOCALAPPDATA\Android\Sdk"
$ADB = "$SDK\platform-tools\adb.exe"
$EMULATOR = "$SDK\emulator\emulator.exe"
$AVD = "Pixel9Pro"

Write-Host "Starting emulator ($AVD)..."
Start-Process $EMULATOR -ArgumentList "-avd", $AVD -WindowStyle Minimized

Write-Host "Waiting for device to come online..."
do {
    Start-Sleep -Seconds 2
    $status = & $ADB devices 2>$null | Select-String "emulator-\d+\s+device"
} while (-not $status)

Write-Host "Waiting for boot to complete..."
do {
    Start-Sleep -Seconds 2
    $booted = & $ADB shell getprop sys.boot_completed 2>$null
} while ($booted.Trim() -ne "1")

Write-Host "Switching ADB to TCP mode on port 5555..."
& $ADB tcpip 5555

$tailscaleIp = (& tailscale ip -4).Trim()
Write-Host ""
Write-Host "Ready! Connect from Linux with:"
Write-Host "  adb connect ${tailscaleIp}:5555"

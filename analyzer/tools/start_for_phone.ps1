# Starts the analyzer and links every attached phone to it over USB (adb reverse is per device and is
# lost whenever the cable is re-plugged, so re-run this script, or just the adb loop, after that).
$ErrorActionPreference = "Continue"
Set-Location (Join-Path $PSScriptRoot "..")
foreach ($line in (adb devices) -split "`n") {
    if ($line -match "^(\S+)\s+device$") {
        adb -s $Matches[1] reverse tcp:8000 tcp:8000 | Out-Null
        Write-Host "adb reverse tcp:8000 set for $($Matches[1])"
    }
}
$py = if (Test-Path ".venv\Scripts\python.exe") { ".venv\Scripts\python.exe" } else { "python" }
& $py -m uvicorn app.main:app --host 0.0.0.0 --port 8000

$ErrorActionPreference = "Stop"

$ADB = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $ADB)) {
    $ADB = "adb"
}

Write-Host "==> 1. Checking connected Android device..." -ForegroundColor Cyan
& $ADB devices
$devices = & $ADB devices | Where-Object { $_ -match "\tdevice$" }
if (-not $devices) {
    Write-Host "Error: No Android device found. Please ensure USB debugging is enabled and device is connected." -ForegroundColor Red
    exit 1
}

Write-Host "==> 2. Installing fresh SwarSetu Debug APK..." -ForegroundColor Cyan
& $ADB install -r app\build\outputs\apk\debug\app-debug.apk

Write-Host "==> 3. Starting SwarSetu Application..." -ForegroundColor Cyan
& $ADB shell "am force-stop app.swarsetu && am start -n app.swarsetu/.MainActivity"

Write-Host "==> 4. Streaming live STT, Translation & Download logs..." -ForegroundColor Green
Write-Host "You can now download the STT & Translation models internally in the app UI!" -ForegroundColor Yellow
& $ADB logcat -c
& $ADB logcat -v time -s SttModelManager:V IndicTransModelManager:V SW_VOICE:V SW_AUDIO:V SW_STT:V Sravaani10Engine:V TranslationTestVM:V ChatViewModel:V AndroidRuntime:E

#!/usr/bin/env powershell
# ProPaint Build and Run Script

param([switch]$UseCache = $false)

$PackageName = "com.propaint.app"
$ActivityName = "$PackageName/.MainActivity"

Write-Host "Build and run script starting..." -ForegroundColor Green

# 1. Force stop old process
Write-Host "1. Force stopping old app process..." -ForegroundColor Cyan
adb shell am force-stop $PackageName 2>$null
Start-Sleep -Seconds 1

# 2. Uninstall old app
Write-Host "2. Uninstalling old app..." -ForegroundColor Cyan
adb uninstall $PackageName 2>$null
Start-Sleep -Seconds 1

# 3. Build and install
Write-Host "3. Building and installing..." -ForegroundColor Cyan

if ($UseCache) {
    Write-Host "   (Using cache)" -ForegroundColor Yellow
    & "$PSScriptRoot\gradlew.bat" ':app:installDebug'
}
else {
    Write-Host "   (Clean build)" -ForegroundColor Yellow
    & "$PSScriptRoot\gradlew.bat" 'clean' ':app:installDebug'
}

# Check build result
if ($LASTEXITCODE -ne 0) {
    Write-Host "Build failed" -ForegroundColor Red
    exit 1
}

Write-Host "   Installation complete" -ForegroundColor Green
Start-Sleep -Seconds 2

# 4. Launch app
Write-Host "4. Launching app..." -ForegroundColor Cyan
adb shell am start -n $ActivityName

if ($?) {
    Write-Host "Launch successful" -ForegroundColor Green
}
else {
    Write-Host "Launch failed" -ForegroundColor Red
    exit 1
}

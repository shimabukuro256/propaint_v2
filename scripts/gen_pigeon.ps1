# Pigeon コード生成ヘルパー (Windows PowerShell)
#
# Usage:
#   .\scripts\gen_pigeon.ps1
#
# 詳細: docs/PIGEON_MIGRATION.md

$ErrorActionPreference = "Stop"

$flutterDir = Join-Path $PSScriptRoot "..\propaint_flutter"
$inputFile = "pigeons\paint_api.dart"

Push-Location $flutterDir
try {
    if (-not (Test-Path $inputFile)) {
        Write-Error "Pigeon input not found: $inputFile"
        exit 1
    }

    Write-Host "[gen_pigeon] Running: dart run pigeon --input $inputFile" -ForegroundColor Cyan
    dart run pigeon --input $inputFile

    if ($LASTEXITCODE -ne 0) {
        Write-Error "Pigeon codegen failed (exit=$LASTEXITCODE)"
        exit $LASTEXITCODE
    }

    Write-Host ""
    Write-Host "[gen_pigeon] OK. Generated:" -ForegroundColor Green
    Write-Host "  propaint_flutter/lib/services/paint_api.g.dart"
    Write-Host "  app/src/main/java/com/propaint/app/flutter/pigeon/PaintApi.g.kt"
}
finally {
    Pop-Location
}

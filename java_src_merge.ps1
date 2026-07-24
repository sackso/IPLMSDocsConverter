# ===================================================================
# [Java Source Merger for NotebookLM]
# ===================================================================
[CmdletBinding()]
param()

# 1. 스크립트가 실행된 현재 물리적 경로로 작업 디렉터리 동기화
$scriptPath = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location -Path $scriptPath

Write-Host "===================================================================" -ForegroundColor Cyan
Write-Host "🚀 프로젝트 내 자바 소스코드 통합 병합을 시작합니다." -ForegroundColor Cyan
Write-Host "📂 현재 작업 경로: $scriptPath" -ForegroundColor Yellow
Write-Host "===================================================================" -ForegroundColor Cyan
Write-Host ""

$outputFileName = "merged_java_sources.txt"

# 2. 기존 결과 파일이 존재할 경우 깔끔하게 삭제 후 초기화
if (Test-Path $outputFileName) {
    Remove-Item $outputFileName -Force
    Write-Host "♻️ 기존 '$outputFileName' 파일을 삭제하고 갱신합니다." -ForegroundColor Gray
}

# 3. 하위 디렉터리의 모든 .java 파일 재귀 탐색 (target, bin, build, .git 제외)
$javaFiles = Get-ChildItem -Path . -Recurse -Filter *.java | 
    Where-Object { $_.FullName -notmatch '\\(target|bin|build|\.git)\\' }

if ($null -eq $javaFiles -or $javaFiles.Count -eq 0) {
    Write-Host "❌ [알림] 병합할 .java 소스 파일을 찾지 못했습니다." -ForegroundColor Red
    return
}

Write-Host "📊 총 $($javaFiles.Count)개의 .java 소스 파일을 수집했습니다. 병합을 진행합니다..." -ForegroundColor Green

# 4. 파일 수집 및 UTF-8 인코딩으로 단일 파일 생성
$javaFiles | ForEach-Object {
    "// ===================================================================="
    "// File Path: $($_.FullName)"
    "// File Name: $($_.Name)"
    "// ===================================================================="
    Get-Content $_.FullName -Raw -Encoding UTF8
    "`n`n"
} | Out-File -FilePath $outputFileName -Encoding UTF8

if (Test-Path $outputFileName) {
    Write-Host ""
    Write-Host "✅ [성공] 모든 자바 소스가 '$outputFileName' 파일로 병합 완료되었습니다!" -ForegroundColor Green
    Write-Host "👉 생성된 파일을 NotebookLM의 출처(Source)로 등록하여 활용하십시오." -ForegroundColor Yellow
} else {
    Write-Host ""
    Write-Host "❌ [오류] 파일 생성 중 예외가 발생했습니다." -ForegroundColor Red
}
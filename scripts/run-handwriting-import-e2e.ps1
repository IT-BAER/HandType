Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$artifactDir = Join-Path $repoRoot "artifacts\androidTest"
$latestAlias = Join-Path $artifactDir "latest_handwriting_import_e2e_result.png"
$instrumentationLogPath = Join-Path $artifactDir "latest_instrumentation_output.txt"
$summaryPath = Join-Path $artifactDir "latest_run.txt"
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$localArtifact = Join-Path $artifactDir "handwriting-import-e2e-result_$stamp.png"
$localTextArtifact = Join-Path $artifactDir "handwriting-import-e2e-result_$stamp.txt"
$localNameArtifact = Join-Path $artifactDir "handwriting-import-e2e-result_$stamp.name"

$packageName = "com.baer.handtype.debug"
$testPackageName = "com.baer.handtype.debug.test"
$runnerComponent = "$testPackageName/androidx.test.runner.AndroidJUnitRunner"
$debugApk = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
$debugTestApk = Join-Path $repoRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"

New-Item -ItemType Directory -Force -Path $artifactDir | Out-Null

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    $output = & adb @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed.`n$output"
    }
    return $output
}

function Invoke-AdbExecOutToFile {
    param(
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments,
        [Parameter(Mandatory = $true)]
        [string] $DestinationPath
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = "adb"
    $psi.Arguments = ($Arguments | ForEach-Object {
        '"' + ($_ -replace '"', '\"') + '"'
    }) -join ' '
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false

    $process = [System.Diagnostics.Process]::Start($psi)
    $stream = [System.IO.File]::Open(
        $DestinationPath,
        [System.IO.FileMode]::Create,
        [System.IO.FileAccess]::Write,
        [System.IO.FileShare]::None
    )
    try {
        $process.StandardOutput.BaseStream.CopyTo($stream)
    }
    finally {
        $stream.Dispose()
    }
    $process.WaitForExit()

    if ($process.ExitCode -ne 0) {
        throw "adb $($Arguments -join ' ') failed while writing $DestinationPath"
    }
}

Write-Host "Checking connected device..."
$devices = Invoke-Adb @("devices")
$onlineDevices = $devices | Select-String "device$"
if (-not $onlineDevices) {
    throw "No connected Android device found."
}

Write-Host "Building debug APKs..."
Push-Location $repoRoot
try {
    & .\gradlew.bat `
        ":app:assembleDebug" `
        ":app:assembleDebugAndroidTest" `
        "--no-daemon"
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed."
    }
}
finally {
    Pop-Location
}

Write-Host "Installing debug APKs..."
Invoke-Adb @("wait-for-device") | Out-Null
Invoke-Adb @("install", "-r", $debugApk) | Out-Null
Invoke-Adb @("install", "-r", $debugTestApk) | Out-Null

Write-Host "Running instrumentation test..."
$instrumentationOutput = Invoke-Adb @(
    "shell",
    "am",
    "instrument",
    "-w",
    "-e",
    "class",
    "com.baer.handtype.HandwritingImportE2eTest",
    $runnerComponent
)
$instrumentationOutput | Set-Content -Path $instrumentationLogPath

# `adb shell am instrument` returns exit 0 even when tests fail; inspect output.
$instrumentationText = ($instrumentationOutput | Out-String)
if ($instrumentationText -match "INSTRUMENTATION_FAILED" -or
    $instrumentationText -match "FAILURES!!!" -or
    $instrumentationText -match "Process crashed" -or
    $instrumentationText -notmatch "OK \(\d+ test") {
    Write-Host $instrumentationText
    throw "Instrumentation test failed. See $instrumentationLogPath"
}

Write-Host "Locating archived render..."
$historyEntries = Invoke-Adb @("shell", "run-as", $packageName, "ls", "files/history")
$historyImage = $historyEntries |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -like "*.png" } |
    Sort-Object |
    Select-Object -Last 1

if (-not $historyImage) {
    throw "No archived history image found in app sandbox."
}

$historyBase = [System.IO.Path]::GetFileNameWithoutExtension($historyImage)
Invoke-AdbExecOutToFile @(
    "exec-out",
    "run-as",
    $packageName,
    "cat",
    "files/history/$historyImage"
) $localArtifact

$sourceText = Invoke-Adb @("shell", "run-as", $packageName, "cat", "files/history/$historyBase.txt")
$templateName = Invoke-Adb @("shell", "run-as", $packageName, "cat", "files/history/$historyBase.name")

$sourceText | Set-Content -Path $localTextArtifact
$templateName | Set-Content -Path $localNameArtifact
Copy-Item $localArtifact $latestAlias -Force

Write-Host "Cleaning device-side test artifacts..."
# Pull sheet_debug cells before cleanup if present
$cellsDir = Join-Path $repoRoot "artifacts\sheet_debug_pull\cells"
New-Item -ItemType Directory -Force -Path $cellsDir | Out-Null
& adb shell ls "/sdcard/Android/data/$packageName/cache/sheet_debug/cells/" 2>$null | ForEach-Object {
    $file = $_.Trim()
    if ($file -match '\.(png|jpg)$') {
        & adb exec-out cat "/sdcard/Android/data/$packageName/cache/sheet_debug/cells/$file" > "$cellsDir/$file" 2>$null
    }
}
& adb shell rm -r "/sdcard/Android/data/$packageName/cache/sheet_debug" 2>$null | Out-Null
& adb shell run-as $packageName rm -r files/history files/user_templates cache 2>$null | Out-Null

@(
    "Instrumentation log: $instrumentationLogPath"
    "Artifact: $localArtifact"
    "Latest alias: $latestAlias"
    "Source text: $localTextArtifact"
    "Template name: $localNameArtifact"
    "History image: $historyImage"
) | Set-Content -Path $summaryPath

Write-Host ""
Write-Host "Done."
Write-Host "Instrumentation log: $instrumentationLogPath"
Write-Host "Artifact: $localArtifact"
Write-Host "Latest alias: $latestAlias"
Write-Host "Source text: $localTextArtifact"
Write-Host "Template name: $localNameArtifact"
Write-Host "History image: $historyImage"

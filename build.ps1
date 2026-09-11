param([switch]$SkipChecks)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
Set-Location -LiteralPath $projectRoot
if (-not $env:JAVA_HOME -and -not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Install JDK 17 and set JAVA_HOME first. See README.md.'
}
$gradleVersion = '8.11.1'
$distributionRoot = Join-Path $projectRoot '.gradle-dist'
$gradleBinary = Join-Path $distributionRoot "gradle-$gradleVersion\bin\gradle.bat"
if (-not (Test-Path -LiteralPath $gradleBinary)) {
    New-Item -ItemType Directory -Force -Path $distributionRoot | Out-Null
    $archive = Join-Path $distributionRoot "gradle-$gradleVersion-bin.zip"
    $address = "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip"
    Write-Host "Downloading Gradle $gradleVersion from services.gradle.org"
    Invoke-WebRequest -UseBasicParsing -Uri $address -OutFile $archive
    $checksum = ((Invoke-WebRequest -UseBasicParsing -Uri "$address.sha256").Content).Trim()
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $archive).Hash -ne $checksum) {
        throw 'Gradle checksum verification failed.'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $distributionRoot -Force
}
if ($SkipChecks) { & $gradleBinary assembleDebug }
else { & $gradleBinary testDebugUnitTest lintDebug assembleDebug }
if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
Write-Host 'APK: app\build\outputs\apk\debug\app-debug.apk'

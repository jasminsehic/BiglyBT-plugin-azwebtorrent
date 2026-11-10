# Build and run a direct-only Tyrus/Java 8 diagnostic on Windows.
# Execute from any PowerShell 7 working directory after cloning this branch.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $repository

Write-Host '--- Java / Maven environment ---'
$toolInfo = & mvn -version 2>&1
if ($LASTEXITCODE -ne 0) { throw 'Maven is not available.' }
$toolInfo | ForEach-Object { Write-Host $_ }
if (($toolInfo -join ' ') -notmatch 'Java version:\s*1\.8\.') {
    throw 'Maven must run with a Java 8 JDK. Set JAVA_HOME to the installed Java 8 JDK first.'
}
Write-Host '--- Fetch BiglyBT 4.1.0.0 host API (only for compilation) ---'
$hostJar = Join-Path $env:TEMP 'BiglyBT-4.1.0.0-host.jar'
if (-not (Test-Path $hostJar)) {
    $hostUrl = 'https://github.com/BiglySoftware/BiglyBT/releases/download/v4.1.0.0/GitHub_BiglyBT.jar'
    Invoke-WebRequest -Uri $hostUrl -OutFile $hostJar
}
if ((Get-Item $hostJar).Length -eq 0) { throw 'Host API JAR is empty.' }

& mvn -B --no-transfer-progress install:install-file "-Dfile=$hostJar" '-DgroupId=com.biglybt' '-DartifactId=biglybt-host' '-Dversion=4.1.0.0' '-Dpackaging=jar' '-DgeneratePom=true'
if ($LASTEXITCODE -ne 0) { throw 'Could not install the BiglyBT host API into the Maven cache.' }

Write-Host '--- Build / run unit and integration tests under Java 8 ---'
& mvn -B --no-transfer-progress clean verify
if ($LASTEXITCODE -ne 0) { throw 'Java 8 build/tests failed; no plugin JAR is ready to install.' }

$jar = Join-Path $repository 'target\azwebtorrent.jar'
$tests = Join-Path $repository 'target\test-classes'
if (-not (Test-Path $jar)) { throw 'Shaded plugin JAR was not produced.' }
if (-not (Test-Path (Join-Path $tests 'org\parg\azureus\plugins\webtorrent\DirectWssProbe.class'))) {
    throw 'DirectWssProbe class was not compiled.'
}
$classPath = "$jar;$tests"

Write-Host '--- Standalone Java 8 direct-only TLS/WebSocket probe ---'
Write-Host 'This separate Java process bypasses outbound proxies without changing BiglyBT settings.'
foreach ($uri in @('wss://tracker.openwebtorrent.com', 'wss://tracker.webtorrent.dev')) {
    Write-Host "=== $uri ==="
    & java -cp $classPath org.parg.azureus.plugins.webtorrent.DirectWssProbe $uri
    if ($LASTEXITCODE -ne 0) { throw "Direct WSS probe failed for $uri" }
}
Write-Host "Build complete: $jar"
Write-Host 'Do not overwrite the installed plugin until the probe results are reviewed.'

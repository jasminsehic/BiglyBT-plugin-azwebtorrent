# Build the complete plugin on Windows using the existing JDK 21, targeting Java 8.
# A Java 8 runtime is needed only for an accurate comparison with BiglyBT.
param(
    [string]$BuildJdk = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot',
    [string]$RuntimeJavaExe = ''
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $repository

$javac = Join-Path $BuildJdk 'bin\javac.exe'
$buildJava = Join-Path $BuildJdk 'bin\java.exe'
foreach ($executable in @($javac, $buildJava)) {
    if (-not (Test-Path $executable)) { throw "Missing JDK executable: $executable" }
}

# Maven uses the same JDK 21 as the older manual javac --release 8 build.
$env:JAVA_HOME = $BuildJdk
$env:Path = "$(Join-Path $BuildJdk 'bin');$env:Path"
$mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mavenCommand) {
    throw 'Maven is not installed/on PATH. Install Apache Maven 3.9+; the new plugin uses Maven to resolve the complete Tyrus 2.0.1 / WebRTC dependency set.'
}

Write-Host '--- Build JDK ---'
& $javac -version
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
& $mavenCommand.Source -version
if ($LASTEXITCODE -ne 0) { throw 'Maven failed' }

# The repository pom.xml sets <maven.compiler.release>8</maven.compiler.release>.
# Do not copy individual classes into azwebtorrent_1.5.5.jar; all newly introduced
# classes and the Java 8-compatible javax.websocket libraries must be packaged.
Write-Host '--- Fetch the BiglyBT 4.1.0.0 host API for compilation ---'
$hostJar = Join-Path $env:TEMP 'BiglyBT-4.1.0.0-host.jar'
if (-not (Test-Path $hostJar) -or (Get-Item $hostJar).Length -eq 0) {
    $hostUrl = 'https://github.com/BiglySoftware/BiglyBT/releases/download/v4.1.0.0/GitHub_BiglyBT.jar'
    Invoke-WebRequest -Uri $hostUrl -OutFile $hostJar
}
if ((Get-Item $hostJar).Length -eq 0) { throw 'BiglyBT host JAR is empty.' }

& $mavenCommand.Source -B --no-transfer-progress install:install-file "-Dfile=$hostJar" '-DgroupId=com.biglybt' '-DartifactId=biglybt-host' '-Dversion=4.1.0.0' '-Dpackaging=jar' '-DgeneratePom=true'
if ($LASTEXITCODE -ne 0) { throw 'Failed to cache BiglyBT 4.1.0.0 host API.' }

Write-Host '--- Compile complete Java 8-targeted plugin with JDK 21 ---'
# The tests are already run on Java 8 in GitHub Actions. Java 21 may trigger
# unrelated module-access restrictions during headless BiglyBT host tests,
# so compile test classes but do not execute the suite using the wrong JVM.
& $mavenCommand.Source -B --no-transfer-progress clean package -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed. Do not install the JAR.' }

$pluginJar = Join-Path $repository 'target\azwebtorrent.jar'
$testClasses = Join-Path $repository 'target\test-classes'
$probeClass = Join-Path $testClasses 'org\parg\azureus\plugins\webtorrent\DirectWssProbe.class'
if (-not (Test-Path $pluginJar)) { throw 'Complete plugin JAR was not produced.' }
if (-not (Test-Path $probeClass)) { throw 'Standalone WSS diagnostic was not compiled.' }

# Check *all* packaged class files, not merely TrackerProxy.class, for Java 8 compatibility.
Write-Host '--- Verify Java 8 bytecode in complete packaged JAR ---'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.ZipFile
$zip = [System.IO.Compression.ZipFile]::OpenRead($pluginJar)
$checked = 0
try {
    foreach ($entry in $zip.Entries) {
        if (-not $entry.FullName.EndsWith('.class') -or
            $entry.FullName.StartsWith('META-INF/versions/') -or
            $entry.FullName.EndsWith('/module-info.class') -or
            $entry.FullName -eq 'module-info.class') { continue }

        $stream = $entry.Open()
        try {
            $header = [byte[]]::new(8)
            if ($stream.Read($header, 0, 8) -ne 8 -or
                $header[0] -ne 0xCA -or $header[1] -ne 0xFE -or
                $header[2] -ne 0xBA -or $header[3] -ne 0xBE) {
                throw "Invalid class header: $($entry.FullName)"
            }
            $majorVersion = ([int]$header[6] -shl 8) + [int]$header[7]
            if ($majorVersion -gt 52) {
                throw "$($entry.FullName) uses class version $majorVersion (Java 8 maximum: 52)"
            }
            $checked++
        }
        finally {
            $stream.Dispose()
        }
    }
}
finally {
    $zip.Dispose()
}
Write-Host "Checked $checked packaged classes: compatible with Java 8 bytecode."

# The probe is run in its *own* process. No BiglyBT proxy settings are changed.
$runtime = $RuntimeJavaExe
if (-not $runtime) {
    foreach ($candidate in @(
        'C:\Program Files\BiglyBT\jre\bin\java.exe',
        'C:\Program Files\BiglyBT\runtime\bin\java.exe',
        'C:\Program Files\BiglyBT\java\bin\java.exe'
    )) {
        if (Test-Path $candidate) { $runtime = $candidate; break }
    }
}
if (-not $runtime) {
    Write-Warning 'Java 8 runtime not found automatically. Running the probe with JDK 21 is useful for comparison but does not prove Java 8 behaviour.'
    $runtime = $buildJava
}
if (-not (Test-Path $runtime)) { throw "Java runtime not found: $runtime" }
Write-Host "--- Direct-only WebSocket probe using $runtime ---"
& $runtime -version
if ($LASTEXITCODE -ne 0) { throw 'Java runtime failed.' }

$classPath = "$pluginJar;$testClasses"
$failures = @()
foreach ($uri in @('wss://tracker.openwebtorrent.com', 'wss://tracker.webtorrent.dev')) {
    foreach ($ipv6First in @($false, $true)) {
        $mode = if ($ipv6First) { 'IPv6-first' } else { 'Default Java address order' }
        Write-Host "=== $uri ($mode) ==="
        $options = @()
        if ($ipv6First) { $options += '-Djava.net.preferIPv6Addresses=true' }
        & $runtime @options -cp $classPath org.parg.azureus.plugins.webtorrent.DirectWssProbe $uri
        if ($LASTEXITCODE -ne 0) {
            $failures += "$uri ($mode)"
            Write-Warning "WSS probe failed for $uri ($mode). Continuing comparison."
        }
    }
}

Write-Host "Complete Java 8-compatible build: $pluginJar"
Write-Host 'Do not deploy the experimental plugin until the direct TCP/TLS/WSS results have been reviewed.'
if ($failures.Count) {
    throw "WSS diagnostic failures: $($failures -join ', ')"
}

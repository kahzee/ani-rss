param([int]$Port = 7789, [switch]$NoBrowser, [string]$Ffprobe = '')
$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskJar = Join-Path $taskRoot 'ani-rss.jar'
if (!(Test-Path -LiteralPath $taskJar)) { $taskJar = Join-Path $taskRoot 'ani-rss-application\target\ani-rss.jar' }
if (!(Test-Path -LiteralPath $taskJar)) { throw 'Missing ani-rss.jar. Build the project or extract the ready-to-run package first.' }
if ($Port -lt 1024 -or $Port -gt 65535) { throw 'Port must be between 1024 and 65535.' }
$taskRuntime = Join-Path $taskRoot '.runtime'
$taskJava = Get-ChildItem -Path $taskRuntime -Filter java.exe -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
if (!$taskJava -and $env:JAVA_HOME) {
    $taskCandidate = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (Test-Path -LiteralPath $taskCandidate) { $taskJava = $taskCandidate }
}
if (!$taskJava) { $taskCommand = Get-Command java.exe -ErrorAction SilentlyContinue; if ($taskCommand) { $taskJava = $taskCommand.Source } }
if ($taskJava) {
    # Windows PowerShell treats Java's version text on stderr as NativeCommandError.
    $taskPreviousErrorPreference = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; $taskVersion = (& $taskJava -version 2>&1 | Out-String) }
    finally { $ErrorActionPreference = $taskPreviousErrorPreference }
    if ($taskVersion -notmatch 'version "(\d+)' -or [int]$Matches[1] -lt 25) { $taskJava = $null }
}
if (!$taskJava) {
    Write-Host 'Downloading the official Eclipse Temurin Java 25 runtime (first launch only)...'
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $taskAssets = Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/25/hotspot?architecture=x64&image_type=jre&os=windows&vendor=eclipse'
    $taskPackage = $taskAssets[0].binary.package
    if (!$taskPackage.link -or !$taskPackage.checksum) { throw 'No official runtime package is available.' }
    New-Item -ItemType Directory -Path $taskRuntime -Force | Out-Null
    $taskArchive = Join-Path $taskRuntime 'temurin.zip'
    Invoke-WebRequest $taskPackage.link -OutFile $taskArchive -UseBasicParsing
    if ((Get-FileHash -LiteralPath $taskArchive -Algorithm SHA256).Hash -ne $taskPackage.checksum) { throw 'Runtime checksum verification failed.' }
    Expand-Archive -LiteralPath $taskArchive -DestinationPath $taskRuntime -Force
    Remove-Item -LiteralPath $taskArchive
    $taskJava = Get-ChildItem -LiteralPath $taskRuntime -Filter java.exe -Recurse | Select-Object -First 1 -ExpandProperty FullName
}
$taskRun = Join-Path $taskRoot '.run'
New-Item -ItemType Directory -Path $taskRun -Force | Out-Null
$taskPidFile = Join-Path $taskRun 'server.pid'
if (Test-Path -LiteralPath $taskPidFile) {
    $taskPrevious = Get-Process -Id ([int](Get-Content -LiteralPath $taskPidFile)) -ErrorAction SilentlyContinue
    if ($taskPrevious -and $taskPrevious.Path -eq $taskJava) { Write-Host 'The application is already running. Use the address printed by the previous launch.'; exit 0 }
}
$taskConfig = Join-Path $taskRoot 'config'
if (!$Ffprobe) {
    $taskProbeCommand = Get-Command ffprobe.exe -ErrorAction SilentlyContinue
    if ($taskProbeCommand) { $Ffprobe = $taskProbeCommand.Source }
    else {
        $taskProbeCandidate = Join-Path $taskRuntime 'ffmpeg\bin\ffprobe.exe'
        if (Test-Path -LiteralPath $taskProbeCandidate) { $Ffprobe = $taskProbeCandidate }
    }
}
if (!$Ffprobe -or !(Test-Path -LiteralPath $Ffprobe -PathType Leaf)) {
    throw 'Media validation requires ffprobe. Install FFmpeg and add its bin folder to PATH, or run start-simple.ps1 -Ffprobe C:\path\to\ffprobe.exe.'
}
$taskArguments = '-Dfile.encoding=UTF-8 "-Dsimple.ffprobe=' + $Ffprobe + '" -Xmx512m -jar "' + $taskJar + '" "--config=' + $taskConfig + '" --server.address=127.0.0.1 --server.port=' + $Port
$taskProcess = Start-Process -FilePath $taskJava -ArgumentList $taskArguments -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskRun 'stdout.log') -RedirectStandardError (Join-Path $taskRun 'stderr.log') -PassThru
$taskProcess.Id | Set-Content -LiteralPath $taskPidFile
$taskUrl = 'http://127.0.0.1:' + $Port + '/#/follow'
$taskReady = $false
for ($taskAttempt = 0; $taskAttempt -lt 60; $taskAttempt++) {
    if ($taskProcess.HasExited) { throw ('Startup failed. See ' + (Join-Path $taskRun 'stdout.log')) }
    try { $taskResponse = Invoke-WebRequest ('http://127.0.0.1:' + $Port + '/api/ping') -UseBasicParsing -TimeoutSec 1; if ($taskResponse.StatusCode -eq 200) { $taskReady = $true; break } } catch {}
    Start-Sleep -Milliseconds 500
}
if (!$taskReady) { throw ('Startup is taking longer than expected. See ' + (Join-Path $taskRun 'stdout.log')) }
Write-Host ('Ready: ' + $taskUrl)
Write-Host 'Local mode: no login required. Configuration and resource history are stored in the config folder.'
if (!$NoBrowser) { Start-Process $taskUrl }

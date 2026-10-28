param([switch]$SkipBuild,[switch]$SkipBrowser)
$ErrorActionPreference='Stop'
$repoPath=Split-Path -Parent $PSScriptRoot
Set-Location $repoPath
if(!$SkipBuild) { & .\mvnw.cmd verify;if($LASTEXITCODE -ne 0) { throw 'Platform build failed.' } }
$runPath=Join-Path $repoPath ('.tools/platform-e2e/'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $runPath,(Join-Path $runPath 'config-repo'),(Join-Path $runPath 'traces') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repoPath 'deploy/config-repo/application-cloud.yml') -Destination (Join-Path $runPath 'config-repo/application-cloud.yml')
Copy-Item -LiteralPath (Join-Path $repoPath 'deploy/config-repo/application.yml') -Destination (Join-Path $runPath 'config-repo/application.yml')
$utf8=[Text.UTF8Encoding]::new($false)
$owned=@{}
$http=[Net.Http.HttpClient]::new();$http.Timeout=[TimeSpan]::FromSeconds(5)
function New-IsolatedSecret {
    $bytes=New-Object byte[] 32;$rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try{$rng.GetBytes($bytes)}finally{$rng.Dispose()}
    return -join ($bytes | ForEach-Object {$_.ToString('x2')})
}
$registryPassword=New-IsolatedSecret;$configPassword=New-IsolatedSecret
foreach($port in @(19161,19188,19118)) {
    $socket=[Net.Sockets.TcpClient]::new()
    try { if($socket.ConnectAsync('127.0.0.1',$port).Wait(150) -and $socket.Connected) { throw "Platform test port $port is occupied." } }
    catch [System.AggregateException] { } finally { $socket.Dispose() }
}
function Start-Isolated([string]$Name,[string[]]$Arguments,[string]$HealthUrl,[string]$Identity) {
    $stdout=Join-Path $runPath ($Name+'.out.log');$stderr=Join-Path $runPath ($Name+'.err.log')
    $process=Start-Process -FilePath (Get-Command java -CommandType Application).Source -ArgumentList $Arguments -WorkingDirectory $repoPath -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $owned[$Name]=@{processId=$process.Id;identity=$Identity}
    $deadline=[DateTime]::UtcNow.AddSeconds(60)
    while([DateTime]::UtcNow -lt $deadline) {
        if($process.HasExited) { throw "$Name exited; inspect $runPath" }
        try {
            $response=$http.GetAsync($HealthUrl).GetAwaiter().GetResult()
            try { if($response.IsSuccessStatusCode) { Write-Output "$Name ready with isolated configuration.";return } }
            finally { $response.Dispose() }
        } catch { }
        Start-Sleep -Milliseconds 250
    }
    throw "$Name readiness timed out; inspect $runPath"
}
try {
    $collector=Join-Path $repoPath 'tools/local/TraceAcceptanceCollector.java'
    Start-Isolated -Name 'collector' -Arguments @(('"'+$collector+'"'),'19118',('"'+(Join-Path $runPath 'traces')+'"')) -HealthUrl 'http://127.0.0.1:19118/health' -Identity $collector
    foreach($name in @('registry','config-server')) {
        $jar=Join-Path $runPath ("taskflow-$name.jar")
        Copy-Item -LiteralPath (Join-Path $repoPath "taskflow-$name/target/taskflow-$name-0.0.1-SNAPSHOT.jar") -Destination $jar
        $yaml=Join-Path $runPath ($name+'.yml')
        if($name -eq 'registry') {
            $configuration=@"
spring:
  config:
    import: ""
server:
  port: 19161
taskflow:
  registry:
    username: platform-registry
    password: "$registryPassword"
eureka:
  server:
    enable-self-preservation: false
    eviction-interval-timer-in-ms: 1000
    response-cache-update-interval-ms: 500
"@
            $port=19161
        } else {
            $configuration=@"
spring:
  config:
    import: ""
  cloud:
    config:
      server:
        native:
          search-locations: "file:$($runPath.Replace('\','/'))/config-repo/"
server:
  port: 19188
taskflow:
  config:
    username: platform-config
    password: "$configPassword"
"@
            $port=19188
        }
        [IO.File]::WriteAllText($yaml,$configuration,$utf8)
        Start-Isolated -Name $name -Arguments @('-jar',('"'+$jar+'"'),('"--spring.config.additional-location=file:'+$yaml.Replace('\','/')+'"')) -HealthUrl ("http://127.0.0.1:$port/actuator/health") -Identity $jar
    }
    $settings=@{
        registryUrl="http://platform-registry:$($registryPassword)@127.0.0.1:19161/eureka/"
        registryApi='http://127.0.0.1:19161/eureka/';registryUser='platform-registry';registryPassword=$registryPassword
        configUrl='http://127.0.0.1:19188';configUser='platform-config';configPassword=$configPassword
        traceEndpoint='http://127.0.0.1:19118/v1/traces';traceDirectory=(Join-Path $runPath 'traces')
    }
    $settingsFile=Join-Path $runPath 'settings.json'
    [IO.File]::WriteAllText($settingsFile,($settings | ConvertTo-Json -Depth 10),$utf8)
    & (Join-Path $PSScriptRoot 'test-execution.ps1') -SkipBuild -SkipBrowser:$SkipBrowser -PlatformSettingsPath $settingsFile
    Write-Output "Platform evidence: $runPath"
} finally {
    foreach($name in @('config-server','registry','collector')) {
        if(!$owned.ContainsKey($name)) { continue }
        $entry=$owned[$name];$process=Get-CimInstance Win32_Process -Filter ('ProcessId = '+$entry.processId)
        if($process) {
            if(!$process.CommandLine.Contains($entry.identity)) { throw 'Platform test PID identity mismatch.' }
            Stop-Process -Id $entry.processId;Wait-Process -Id $entry.processId -Timeout 10 -ErrorAction SilentlyContinue
        }
    }
    $http.Dispose()
}

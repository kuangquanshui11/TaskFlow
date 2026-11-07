param([switch]$SkipBuild, [switch]$SkipBrowser, [switch]$InjectWorkerResultSaveFailure, [switch]$IncludeExecutionLease, [switch]$IncludeWorkerCluster, [switch]$IncludeConfigReconciliation, [switch]$IncludeAttemptTimeline,
    [ValidateSet('http','rabbitmq')][string]$Transport = 'http', [string]$MqSettingsPath,[string]$PlatformSettingsPath,
    [ValidateRange(1024,65535)][int]$IdentityPort = 19190)
$ErrorActionPreference = 'Stop'
if ($IncludeWorkerCluster -and ($Transport -ne 'rabbitmq' -or $PlatformSettingsPath)) {
    throw 'Worker cluster acceptance requires standalone RabbitMQ mode and an isolated test vhost.'
}
if ($IncludeAttemptTimeline -and ($Transport -ne 'http' -or $PlatformSettingsPath)) {
    throw 'Attempt timeline crash acceptance requires isolated standalone HTTP mode.'
}
$repoPath = Split-Path -Parent $PSScriptRoot
Set-Location $repoPath
$platform=$null
if($PlatformSettingsPath) { $platform=Get-Content -LiteralPath $PlatformSettingsPath -Raw | ConvertFrom-Json }
$mqSettings = $null
if ($Transport -eq 'rabbitmq') {
    if (!$MqSettingsPath -or !(Test-Path -LiteralPath $MqSettingsPath)) { throw 'RabbitMQ acceptance requires isolated MqSettingsPath; use tools/test-mq.ps1.' }
    $mqSettings = Get-Content -LiteralPath $MqSettingsPath -Raw | ConvertFrom-Json
    if ($mqSettings.virtualHost -notmatch '^taskflow_it_[a-zA-Z0-9_-]+$' -or !$mqSettings.addresses -or !$mqSettings.username -or !$mqSettings.password) {
        throw 'MQ tests require an isolated taskflow_it_ vhost and explicit connection settings.'
    }
}
$javaExe = (Get-Command java -CommandType Application).Source
$ports = @{ admin = 19080; scheduler = 19081; worker = 19082; gateway = 19088 }
if ($IncludeWorkerCluster) { $ports['worker2'] = 19083 }
$nginxPort = 8091
foreach ($testPort in @($ports.Values) + @($nginxPort)) {
    $probe = [Net.Sockets.TcpClient]::new()
    try { if ($probe.ConnectAsync('127.0.0.1', $testPort).Wait(150) -and $probe.Connected) { throw "Test port $testPort is occupied." } }
    catch [System.AggregateException] { }
    finally { $probe.Dispose() }
}
if (!$SkipBuild) {
    & .\mvnw.cmd verify
    if ($LASTEXITCODE -ne 0) { throw 'Backend verify failed.' }
}
$h2Jar = Get-ChildItem -Path (Join-Path $env:USERPROFILE '.m2/repository/com/h2database/h2/*/h2-*.jar') |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (!$h2Jar) { throw 'H2 test driver missing; run Maven tests first.' }
$runRoot = Join-Path $repoPath ('.tools/execution-e2e/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $runRoot -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
function New-TestSecret {
    $bytes = New-Object byte[] 32; $rng.GetBytes($bytes)
    return [Convert]::ToBase64String($bytes).Replace('+','-').Replace('/','_').TrimEnd('=')
}
$controlToken = New-TestSecret; $dispatchToken = New-TestSecret; $reportToken = New-TestSecret
$nodeToken = New-TestSecret
$processes = @{}
$serviceFiles = @{}
$resultFailureClasses = $null
$resultFailureMigrations = $null
$nginxStarted = $false
$http = [Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(8)
$evidence = [Collections.Generic.List[object]]::new()
$mutatedEnvironment = @{}
foreach ($name in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD',
        'SPRING_DATASOURCE_DRIVER_CLASS_NAME','SPRING_CONFIG_IMPORT','SPRING_PROFILES_ACTIVE','SERVER_PORT',
        'SPRING_FLYWAY_ENABLED','TASKFLOW_EXECUTION_E2E','TASKFLOW_EXECUTION_EVIDENCE_DIR')) {
    $mutatedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    Remove-Item ('Env:' + $name) -ErrorAction SilentlyContinue
}
function Call-Api([string]$Method, [string]$Url, $Body = $null, [string]$Token = '',[string]$BasicAuthorization = '',[string]$TraceParent = '') {
    if($Url.StartsWith('http://127.0.0.1:19080/api/')) { $Url=$Url.Replace('http://127.0.0.1:19080/api/','http://127.0.0.1:19088/api/') }
    if ($Url.StartsWith('http://127.0.0.1:19088/api/v1/') -and !$BasicAuthorization) {
        $BasicAuthorization = Get-AcceptanceAuthorization
        # Respect the production token buckets; never hide a 429 by replaying a POST.
        $gap = if ($Method -eq 'POST' -and $Url -match '/(runs/insert|job-sync/updateRetry)$') { 1050 }
            elseif ($Method -eq 'POST') { 220 } else { 55 }
        Start-Sleep -Milliseconds $gap
    }
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), $Url)
    $request.Headers.Accept.ParseAdd('application/json')
    if ($Token) { $null = $request.Headers.TryAddWithoutValidation('X-Taskflow-Token', $Token) }
    if($BasicAuthorization) { $null=$request.Headers.TryAddWithoutValidation('Authorization',$BasicAuthorization) }
    if($TraceParent) { $null=$request.Headers.TryAddWithoutValidation('traceparent',$TraceParent) }
    if ($null -ne $Body) { $request.Content = [Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 40 -Compress), [Text.Encoding]::UTF8, 'application/json') }
    try {
        $response = $http.SendAsync($request).GetAwaiter().GetResult()
        try {
            $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            $data = if ($text) { $text | ConvertFrom-Json } else { $null }
            return [pscustomobject]@{ status = [int]$response.StatusCode; body = $data }
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}
function Assert-Check([bool]$Value, [string]$Name) {
    if (!$Value) { throw "Execution acceptance failed: $Name" }
    $evidence.Add([pscustomobject]@{ name = $Name; result = 'PASS' })
    Write-Output ("PASS " + $Name)
}
function Start-TestService([string]$Name) {
    $file = $serviceFiles[$Name]
    $out = Join-Path $runRoot ($Name + '-' + (Get-Date -Format 'HHmmssfff') + '.out.log')
    $err = $out.Replace('.out.log','.err.log')
    $loaderPaths = $h2Jar.FullName
    if ($Name -eq 'worker' -and $resultFailureClasses) { $loaderPaths += ',' + $resultFailureClasses }
    $javaArguments = @(('-Dloader.path="' + $loaderPaths + '"'),
        ('"-Dspring.config.additional-location=file:' + $file.yaml.Replace('\','/') + '"'),
        '-cp', ('"' + $file.jar + '"'), 'org.springframework.boot.loader.launch.PropertiesLauncher')
    if ($platform) {
        # Config imports are resolved for every profile document during bootstrap.
        # Supply the isolated endpoint before any application-cloud.yml is read.
        $javaArguments = @('-DTASKFLOW_CONFIG_SERVER_URL=' + $platform.configUrl) + $javaArguments
    }
    $process = Start-Process -FilePath $javaExe -ArgumentList $javaArguments -WorkingDirectory $repoPath -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err
    $processes[$Name] = [pscustomobject]@{ id = $process.Id; jar = $file.jar; log = $out; process = $process }
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $readyDeadline) {
        if ($process.HasExited) { throw "$Name exited; inspect $runRoot" }
        try {
            $health = Call-Api 'GET' ("http://127.0.0.1:" + $ports[$Name] + '/actuator/health')
            if ($health.status -eq 200 -and $health.body.status -eq 'UP') {
                $stream = [IO.File]::Open($out, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
                $reader = [IO.StreamReader]::new($stream)
                try { $log = $reader.ReadToEnd() } finally { $reader.Dispose() }
                if ($Name -ne 'gateway' -and !$log.Contains('jdbc:h2:file:' + $runRoot.Replace('\','/') + '/' + $Name + '-db')) { throw 'Database readiness guard failed.' }
                Write-Output "$Name ready with isolated test configuration."
                return
            }
        } catch { if ($_.Exception.Message -match 'Database readiness guard') { throw } }
        Start-Sleep -Milliseconds 250
    }
    throw "$Name readiness timed out; inspect $runRoot"
}
function Stop-TestService([string]$Name) {
    if (!$processes.ContainsKey($Name)) { return }
    $entry = $processes[$Name]
    # Retain the handle we created instead of finding arbitrary processes by name/PID.
    $process = $entry.process
    if (!$process.HasExited) { $process.Kill(); $process.WaitForExit(10000) | Out-Null }
    $process.Dispose()
    $processes.Remove($Name)
}
function New-TestJob([string]$Handler, [int]$Attempts = 1, [int]$Timeout = 10, [int]$Delay = 50) {
    $body = @{ name = "execution-e2e-$Handler"; handlerKey = $Handler; payload = @{ durationMillis = $Delay };
        maxAttempts = $Attempts; timeoutSeconds = $Timeout; retryIntervalSeconds = 1 }
    $result = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/jobs/insert' $body
    if ($result.status -ne 201) { throw 'Test Job creation failed.' }
    return $result.body.data
}
function New-TestRun($Job) {
    $body = @{ jobId = $Job.id; configVersion = $Job.configVersion; requestKey = [Guid]::NewGuid().ToString() }
    $result = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/runs/insert' $body
    if ($result.status -ne 202) { throw 'Test Run creation failed.' }
    return [pscustomobject]@{ request = $body; run = $result.body.data }
}
function Wait-TestRun([string]$Id) {
    for ($i = 0; $i -lt 80; $i++) {
        $result = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/runs/selectById?id=" + $Id)
        if ($result.status -eq 200 -and $result.body.data.state -in @('SUCCEEDED','FAILED')) { return $result.body.data }
        Start-Sleep -Milliseconds 250
    }
    throw 'Run did not reach a terminal state.'
}
function Wait-TestAttemptEvents([string]$RunId, [string]$AttemptId, [scriptblock]$Ready) {
    $uri = "http://127.0.0.1:19080/api/v1/runs/selectAttemptEvents?runId=$RunId&attemptId=$AttemptId"
    for ($i = 0; $i -lt 80; $i++) {
        $result = Call-Api 'GET' $uri
        if ($result.status -eq 200 -and (& $Ready $result.body.data)) { return $result.body.data }
        Start-Sleep -Milliseconds 250
    }
    throw "Attempt timeline did not reach the expected state for $AttemptId."
}
function Wait-TestSync([long]$JobId, [string]$State, [long]$Version) {
    for ($i = 0; $i -lt 80; $i++) {
        $result = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/job-sync/selectByJobId?id=" + $JobId)
        if ($result.status -eq 200 -and $result.body.data.state -eq $State -and $result.body.data.configVersion -eq $Version) { return $result.body.data }
        Start-Sleep -Milliseconds 250
    }
    throw "Job $JobId sync did not reach $State version $Version."
}
function Wait-TestNode([string]$State, [string]$WorkerId = 'worker-default') {
    for ($i=0; $i -lt 80; $i++) {
        $response=Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/workers/selectById?workerId=" + $WorkerId)
        if ($response.status -eq 200 -and $response.body.data.status -eq $State) { return $response.body.data }
        Start-Sleep -Milliseconds 250
    }
    throw "Worker did not become $State."
}
$accessProcesses = @{}
$accessRedisPorts = @{ 'session-redis' = 16379; 'rate-redis' = 16380 }
. (Join-Path $PSScriptRoot 'lib/execution-access.ps1')
try {
    Initialize-AcceptanceAccess
    if ($InjectWorkerResultSaveFailure) {
        $resultFailureClasses = Join-Path $runRoot 'result-save-classes'
        $resultFailureMigrations = Join-Path $runRoot 'result-save-migrations'
        New-Item -ItemType Directory -Path $resultFailureClasses, $resultFailureMigrations -Force | Out-Null
        $javacExe = Join-Path (Split-Path -Parent $javaExe) 'javac.exe'
        & $javacExe -cp $h2Jar.FullName -d $resultFailureClasses (Join-Path $PSScriptRoot 'local/FailFirstResultSave.java')
        if ($LASTEXITCODE -ne 0) { throw 'Result persistence fault injector compilation failed.' }
        [IO.File]::WriteAllText((Join-Path $resultFailureMigrations 'R__inject_result_save_failure.sql'),
            'CREATE TRIGGER worker_result_save_failure BEFORE UPDATE ON worker_execution FOR EACH ROW CALL "FailFirstResultSave";', $utf8)
    }
    foreach ($name in @('admin','scheduler','worker')) {
        $jar = Join-Path $runRoot ("taskflow-$name.jar")
        Copy-Item -LiteralPath (Join-Path $repoPath "taskflow-$name/target/taskflow-$name-0.0.1-SNAPSHOT.jar") -Destination $jar
        $yaml = Join-Path $runRoot ($name + '.yml')
        $migration = if ($name -eq 'admin') { 'false' } else { 'true' }
        $migrationLocations = if ($name -eq 'worker' -and $resultFailureMigrations) {
            '    locations: "classpath:db/migration,filesystem:' + $resultFailureMigrations.Replace('\','/') + '"'
        } else { '' }
        $schemaPath = (Join-Path $repoPath 'taskflow-admin/src/test/resources/db/test-schema.sql').Replace('\','/')
        $sqlInit = if ($name -eq 'admin') { "  sql:" + [Environment]::NewLine + "    init:" + [Environment]::NewLine + "      mode: always" + [Environment]::NewLine + "      schema-locations: 'file:$schemaPath'" } else { '' }
        $mqYaml = ''
        $mqHealth = 'false'
        if ($Transport -eq 'rabbitmq' -and $name -ne 'admin') {
            $mqHealth = 'true'
            $mqUser = $mqSettings.username; $mqPassword = $mqSettings.password
            if ($mqSettings.serviceCredentials -and $mqSettings.serviceCredentials.$name) {
                $role = $mqSettings.serviceCredentials.$name
                if (!$role.username -or !$role.password) { throw "Incomplete MQ credentials for $name." }
                $mqUser = $role.username; $mqPassword = $role.password
            }
            $mqYaml = "  rabbitmq:`n    addresses: $($mqSettings.addresses | ConvertTo-Json -Compress)`n    virtual-host: $($mqSettings.virtualHost | ConvertTo-Json -Compress)`n    username: $($mqUser | ConvertTo-Json -Compress)`n    password: $($mqPassword | ConvertTo-Json -Compress)"
        }
        $testImport=''
        $cloudYaml=''
        $eurekaYaml="eureka:`n  client:`n    enabled: false"
        $traceYaml=''
        if($platform) {
            $testImport='configserver:'+$platform.configUrl
            $cloudYaml="  profiles:`n    active: cloud`n  cloud:`n    config:`n      enabled: true`n      username: $($platform.configUser)`n      password: `"$($platform.configPassword)`""
            $eurekaYaml="eureka:`n  client:`n    enabled: true`n    registry-fetch-interval-seconds: 1`n    initial-instance-info-replication-interval-seconds: 1`n    instance-info-replication-interval-seconds: 2`n    service-url:`n      defaultZone: `"$($platform.registryUrl)`"`n  instance:`n    lease-renewal-interval-in-seconds: 1`n    lease-expiration-duration-in-seconds: 4"
            $traceYaml="  tracing:`n    sampling:`n      probability: 1.0`n    export:`n      otlp:`n        enabled: true`n  opentelemetry:`n    tracing:`n      export:`n        otlp:`n          endpoint: `"$($platform.traceEndpoint)`"`n  info:`n    env:`n      enabled: true`n  endpoints:`n    web:`n      exposure:`n        include: health,info"
        }
        $accessImport = if ($name -eq 'admin') { 'file:' + (Join-Path $PSScriptRoot 'acceptance/application-access-admin.yml').Replace('\','/') } else { '' }
        $testImport = @($testImport, $accessImport | Where-Object { $_ }) -join ','
        $executionLeaseYaml = if ($IncludeExecutionLease -or $IncludeWorkerCluster -or $IncludeAttemptTimeline) { "    lease:`n      duration: 10s`n      renewal-interval: 500ms" } else { '' }
        $configuration = @"
spring:
  config:
    import: "$testImport"
$cloudYaml
$mqYaml
  datasource:
    url: "jdbc:h2:file:$($runRoot.Replace('\','/'))/$name-db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0"
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
    hikari:
      connection-init-sql: "SET TIME ZONE 'UTC'"
  flyway:
    enabled: $migration
$migrationLocations
$sqlInit
server:
  port: $($ports[$name])
management:
$traceYaml
  health:
    redis:
      enabled: false
    rabbit:
      enabled: $mqHealth
taskflow:
  nodes:
    enabled: true
    worker-id: worker-default
    display-name: "隔离验收节点"
    target-worker-id: worker-default
    scheduler-url: "http://127.0.0.1:19081"
    node-token: "$nodeToken"
    control-token: "$controlToken"
    offline-timeout: 3s
    heartbeat-interval: 500ms
    initial-delay: 200ms
    connect-timeout: 1s
    request-timeout: 1s
    dispatch-gating-enabled: true
  dispatch:
    transport: $Transport
  job-sync:
    enabled: true
    scheduler-url: "http://127.0.0.1:19081"
    control-token: "$controlToken"
    connect-timeout: 1s
    request-timeout: 1s
    scan-interval: 200ms
    retry-delay: 200ms
    max-retry-delay: 1s
    max-attempts: 3
    claim-lease: 10s
  jobs:
    detail-cache:
      enabled: false
  execution:
$executionLeaseYaml
    scheduler-url: "http://127.0.0.1:19081"
    worker-url: "http://127.0.0.1:19082"
    control-token: "$controlToken"
    dispatch-token: "$dispatchToken"
    report-token: "$reportToken"
    scanning-enabled: true
$eurekaYaml
info:
  taskflow:
    configRevision: "`${taskflow.platform.config-revision:standalone}"
"@
        [IO.File]::WriteAllText($yaml, $configuration, $utf8)
        $serviceFiles[$name] = @{ jar = $jar; yaml = $yaml }
    }
    $gatewayJar=Join-Path $runRoot 'taskflow-gateway.jar'
    Copy-Item -LiteralPath (Join-Path $repoPath 'taskflow-gateway/target/taskflow-gateway-0.0.1-SNAPSHOT.jar') -Destination $gatewayJar
    $gatewayYaml=Join-Path $runRoot 'gateway.yml'
    $gatewayImport='';$gatewayCloud='';$gatewayDiscovery="  cloud:`n    config:`n      enabled: false`n    discovery:`n      client:`n        simple:`n          instances:`n            taskflow-admin:`n              - uri: http://127.0.0.1:19080"
    $gatewayEureka="eureka:`n  client:`n    enabled: false";$gatewayTrace=''
    if($platform) {
        $gatewayImport='configserver:'+$platform.configUrl
        $gatewayCloud="  profiles:`n    active: cloud"
        $gatewayDiscovery="  cloud:`n    config:`n      enabled: true`n      username: $($platform.configUser)`n      password: `"$($platform.configPassword)`""
        $gatewayEureka=$eurekaYaml
        $gatewayTrace=$traceYaml
    }
    $gatewayImport = @($gatewayImport, ('file:' + (Join-Path $PSScriptRoot 'acceptance/application-access-gateway.yml').Replace('\','/')) | Where-Object { $_ }) -join ','
    $gatewayConfiguration=@"
spring:
  config:
    import: "$gatewayImport"
$gatewayCloud
$gatewayDiscovery
server:
  port: 19088
management:
$gatewayTrace
  health:
    redis:
      enabled: false
    discoveryComposite:
      enabled: false
$gatewayEureka
info:
  taskflow:
    configRevision: "`${taskflow.platform.config-revision:standalone}"
"@
    [IO.File]::WriteAllText($gatewayYaml,$gatewayConfiguration,$utf8)
    $serviceFiles['gateway']=@{jar=$gatewayJar;yaml=$gatewayYaml}
    Start-TestService 'scheduler'; Start-TestService 'worker'; Start-TestService 'admin';Start-TestService 'gateway'
    Connect-AcceptanceSession
    & node (Join-Path $PSScriptRoot 'local/execution-security.mjs') security $accessSettingsPath $accessTokensPath $runRoot
    if ($LASTEXITCODE -ne 0) { throw 'JWT and real Redis security acceptance failed.' }
    Assert-Check $true 'Anonymous access, VIEWER permissions, rotation, logout, real Redis TTL expiry and fresh reauthentication pass'
    if($platform) {
        $auth='Basic '+[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($platform.registryUser+':'+$platform.registryPassword))
        $registered=$false
        for($i=0;$i -lt 120;$i++) {
            $apps=Call-Api 'GET' ($platform.registryApi+'apps') $null '' $auth
            $names=@($apps.body.applications.application | ForEach-Object { $_.name })
            if(@('TASKFLOW-ADMIN','TASKFLOW-SCHEDULER','TASKFLOW-WORKER','TASKFLOW-GATEWAY' | Where-Object { $_ -notin $names }).Count -eq 0) { $registered=$true;break }
            Start-Sleep -Milliseconds 250
        }
        Assert-Check $registered 'Gateway and all three business services actually register with Eureka'
        foreach($role in @('admin','scheduler','worker')) {
            $info=Call-Api 'GET' ('http://127.0.0.1:'+$ports[$role]+'/actuator/info')
            Assert-Check ($info.status -eq 200 -and $info.body.taskflow.configRevision -eq 'governance-v1') ("$role loads actual settings from Config Server")
        }
    }
    # 只在首次启动初始化 Admin H2 表；后续重启保留配置和 Outbox。
    $adminYaml = $serviceFiles['admin'].yaml
    [IO.File]::WriteAllText($adminYaml, ([IO.File]::ReadAllText($adminYaml).Replace('mode: always', 'mode: never')), $utf8)
    $unauthorized = Call-Api 'GET' 'http://127.0.0.1:19081/internal/v1/runs/select?jobId=1'
    Assert-Check ($unauthorized.status -eq 401) 'Internal APIs reject unauthenticated requests'
    $firstNode=Wait-TestNode 'ONLINE'
    Assert-Check ($firstNode.dispatchEligible -and $firstNode.handlerKeys -contains 'demoSuccess') 'Worker registers real capabilities and becomes an eligible online target'
    $wrongNodeRole=Call-Api 'POST' 'http://127.0.0.1:19081/internal/v1/workers/updateHeartbeat' @{} $controlToken
    Assert-Check ($wrongNodeRole.status -eq 401) 'Worker heartbeat requires its dedicated node token'
    Assert-Check ((Call-Api 'GET' 'http://127.0.0.1:19088/internal/v1/runs/select').status -eq 404) 'Gateway rejects internal business APIs'
    if($platform) {
        $traceId=[Guid]::NewGuid().ToString('N')
        $traceReply=Call-Api 'GET' 'http://127.0.0.1:19080/api/v1/workers/selectById?workerId=worker-default' $null '' '' ('00-'+$traceId+'-1111111111111111-01')
        Assert-Check ($traceReply.status -eq 200) 'Gateway Admin and Scheduler cooperate through discovered Feign endpoints'
        [IO.File]::WriteAllText((Join-Path $runRoot 'trace-id.txt'),$traceId,$utf8)
        & (Join-Path $PSScriptRoot 'check-trace-evidence.ps1') -TraceDirectory $platform.traceDirectory -TraceId $traceId
        Assert-Check $true 'OTLP collector receives the same trace from Gateway Admin and Scheduler'
    }

    $successJob = New-TestJob 'demoSuccess'
    $success = New-TestRun $successJob
    $duplicate = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/runs/insert' $success.request
    Assert-Check ($duplicate.body.data.id -eq $success.run.id) 'Same request key creates one logical Run'
    $successResult = Wait-TestRun $success.run.id
    Assert-Check ($successResult.state -eq 'SUCCEEDED' -and @($successResult.attempts).Count -eq 1) 'Success handler completes via three real services'
    if ($IncludeExecutionLease) {
        $leaseJob = New-TestJob 'demoSlow' -Timeout 20 -Delay 12000
        $leaseRun = New-TestRun $leaseJob
        $leaseBefore = $null
        for ($i = 0; $i -lt 40; $i++) {
            $observed = (Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/runs/selectById?id=" + $leaseRun.run.id)).body.data
            if ($observed.attempts[0].ownerWorkerId -and $observed.attempts[0].leaseUntil) { $leaseBefore = $observed; break }
            Start-Sleep -Milliseconds 100
        }
        Assert-Check ($null -ne $leaseBefore -and $leaseBefore.attempts[0].ownerWorkerId -eq 'worker-default') 'Execution lease binds the actual Worker before running the Handler'
        $leaseAttempt = $leaseBefore.attempts[0]
        $renewed = $null
        for ($i = 0; $i -lt 40; $i++) {
            $observed = (Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/runs/selectById?id=" + $leaseRun.run.id)).body.data
            if ([DateTimeOffset]::Parse($observed.attempts[0].leaseUntil) -gt [DateTimeOffset]::Parse($leaseAttempt.leaseUntil).AddSeconds(1)) { $renewed = $observed; break }
            Start-Sleep -Milliseconds 100
        }
        Assert-Check ($null -ne $renewed -and $renewed.attempts[0].leaseVersion -eq $leaseAttempt.leaseVersion -and
            $renewed.attempts[0].expiresAt -eq $leaseAttempt.expiresAt -and
            [DateTimeOffset]::Parse($renewed.attempts[0].leaseUntil) -le [DateTimeOffset]::Parse($leaseAttempt.expiresAt)) 'Real HTTP renewal extends only the short lease and preserves the version and hard deadline'
        $wrongOwner = @{ attemptId=$leaseAttempt.id; runId=$leaseBefore.id; leaseVersion=$leaseAttempt.leaseVersion;
            workerId='another-worker'; sessionId=$leaseAttempt.ownerSessionId }
        Assert-Check ((Call-Api 'POST' 'http://127.0.0.1:19081/internal/v1/attempts/updateLease' $wrongOwner $reportToken).status -eq 409) 'Renewal rejects a different Worker'
        $wrongOwner.workerId = 'worker-default'; $wrongOwner.sessionId = [Guid]::NewGuid().ToString()
        Assert-Check ((Call-Api 'POST' 'http://127.0.0.1:19081/internal/v1/attempts/updateLease' $wrongOwner $reportToken).status -eq 409) 'Renewal rejects a different execution session'
        $originalDispatch = @{ attemptId=$leaseAttempt.id; runId=$leaseBefore.id; attemptNumber=$leaseAttempt.attemptNumber;
            leaseVersion=$leaseAttempt.leaseVersion; expiresAt=$leaseAttempt.expiresAt; snapshot=$leaseBefore.snapshot }
        Assert-Check ((Call-Api 'POST' 'http://127.0.0.1:19082/internal/v1/executions/insert' $originalDispatch $dispatchToken).status -eq 202) 'Duplicate dispatch after renewal still matches the original Inbox fingerprint'
        $leaseResult = Wait-TestRun $leaseRun.run.id
        Assert-Check ($leaseResult.state -eq 'SUCCEEDED' -and @($leaseResult.attempts).Count -eq 1 -and
            [DateTimeOffset]::UtcNow -gt [DateTimeOffset]::Parse($leaseAttempt.leaseUntil)) 'Handler succeeds beyond the initial short lease through actual Scheduler and Worker processes'
        $wrongOwner.sessionId = $leaseAttempt.ownerSessionId
        Assert-Check ((Call-Api 'POST' 'http://127.0.0.1:19081/internal/v1/attempts/updateLease' $wrongOwner $reportToken).status -eq 409) 'Terminal Attempt cannot be revived by renewal'
    }
    if ($InjectWorkerResultSaveFailure) {
        $stream = [IO.File]::Open($processes['worker'].log, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        $reader = [IO.StreamReader]::new($stream)
        try { $workerLog = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $savedAttemptId = $successResult.attempts[0].id
        Assert-Check ($workerLog.Contains('code=RESULT_SAVE_RETRY attemptId=' + $savedAttemptId) -and
            $workerLog.Contains('code=RESULT_SAVE_RECOVERED attemptId=' + $savedAttemptId)) 'Actual terminal SQL failure recovers by saving the same Attempt'
        Assert-Check ($successResult.resultCode -eq 'SUCCEEDED' -and $successResult.message -eq '执行成功' -and
            @($successResult.attempts).Count -eq 1) 'Scheduler accepts the original result after Worker database write recovery'
    }
    if ($Transport -eq 'rabbitmq') {
        $attempt = $successResult.attempts[0]
        $event = @{version=1;eventId=$attempt.id;command=@{attemptId=$attempt.id;runId=$successResult.id;
            attemptNumber=$attempt.attemptNumber;leaseVersion=$attempt.leaseVersion;expiresAt=$attempt.expiresAt;snapshot=$successResult.snapshot}}
        $published = @{properties=@{message_id=$attempt.id;type='taskflow.dispatch.v1';content_type='application/json';delivery_mode=2};
            routing_key='worker.default';payload=($event | ConvertTo-Json -Depth 40 -Compress);payload_encoding='string'}
        $basicAuth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($mqSettings.username + ':' + $mqSettings.password))
        $managementBase = $mqSettings.managementUrl.TrimEnd('/') + '/api/exchanges/' + $mqSettings.virtualHost + '/taskflow.dispatch.v1/publish'
        $publishedResult = Invoke-RestMethod -Method Post -Uri $managementBase -Headers @{Authorization="Basic $basicAuth"} -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes(($published | ConvertTo-Json -Depth 40 -Compress)))
        Assert-Check ([bool]$publishedResult.routed) 'Duplicate versioned event is routed through real RabbitMQ'
        Start-Sleep -Milliseconds 500
        $localExecution = Call-Api 'GET' ("http://127.0.0.1:19082/internal/v1/executions/selectById?attemptId=" + $attempt.id) $null $dispatchToken
        $duplicateRun = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/runs/selectById?id=" + $successResult.id)
        Assert-Check ($localExecution.body.state -eq 'SUCCEEDED' -and @($duplicateRun.body.data.attempts).Count -eq 1) 'Duplicate MQ event preserves completed Inbox and original Attempt'
    }

    $failureJob = New-TestJob 'demoFail'
    $failure = New-TestRun $failureJob
    $failed = Wait-TestRun $failure.run.id
    Assert-Check ($failed.state -eq 'FAILED' -and $failed.resultCode -eq 'HANDLER_FAILED') 'Handler failure is persisted and visible'

    $retryJob = New-TestJob 'demoFailOnce' 2
    $retry = New-TestRun $retryJob
    $retried = Wait-TestRun $retry.run.id
    Assert-Check ($retried.state -eq 'SUCCEEDED' -and @($retried.attempts).Count -eq 2 -and $retried.attempts[0].state -eq 'FAILED') 'Business retry reuses Run and creates a new Attempt'

    $timeoutJob = New-TestJob 'demoSlow' 3 1 1600
    $timeout = New-TestRun $timeoutJob
    $timedOut = Wait-TestRun $timeout.run.id
    Assert-Check ($timedOut.state -eq 'FAILED' -and $timedOut.resultCode -eq 'EXECUTION_TIMEOUT' -and @($timedOut.attempts).Count -eq 1) 'Execution timeout is terminal without unsafe automatic retry'

    $updatedBody = @{ id = $successJob.id; configVersion = $successJob.configVersion; job = @{ name = 'changed-after-run'; handlerKey = 'demoFail'; maxAttempts = 1 } }
    $update = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/jobs/update' $updatedBody
    Assert-Check ($update.status -eq 200) 'Job can change independently after execution'
    $snapshot = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/runs/selectById?id=" + $success.run.id)
    Assert-Check ($snapshot.body.data.snapshot.handlerKey -eq 'demoSuccess' -and $snapshot.body.data.configVersion -eq 1) 'Run preserves original configuration snapshot'
    $sameAfterChange = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/runs/insert' $success.request
    Assert-Check ($sameAfterChange.body.data.id -eq $success.run.id) 'Original request remains idempotent after Job changes'
    $attempt = $successResult.attempts[0]
    $stale = Call-Api 'POST' 'http://127.0.0.1:19081/internal/v1/attempts/updateResult' @{ attemptId = $attempt.id; runId = $success.run.id; leaseVersion = 999; status = 'SUCCEEDED'; resultCode = 'SUCCEEDED'; message = 'late'; retryable = $false; workerId = $attempt.ownerWorkerId; sessionId = $attempt.ownerSessionId } $reportToken
    Assert-Check ($stale.status -eq 409) 'Stale lease cannot overwrite terminal result'

    Stop-TestService 'worker'
    # A dead process can remain business-online until its heartbeat TTL expires.
    # Test a genuinely pending dispatch, rather than an already leased send
    # whose execution deadline legitimately expires during multi-service boot.
    $null = Wait-TestNode 'OFFLINE'
    $pendingJob = New-TestJob 'demoSuccess'
    $pending = New-TestRun $pendingJob
    $pendingAttemptId = $pending.run.attempts[0].id
    Stop-TestService 'scheduler'; Start-TestService 'scheduler'; Start-TestService 'worker'
    $historyAfterRestart = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/runs/selectById?id=" + $success.run.id)
    Assert-Check ($historyAfterRestart.body.data.state -eq 'SUCCEEDED') 'Scheduler restart preserves completed history'
    $recovered = Wait-TestRun $pending.run.id
    Assert-Check ($recovered.state -eq 'SUCCEEDED' -and $recovered.attempts[0].id -eq $pendingAttemptId) 'Pending dispatch survives Scheduler and Worker restart'

    Stop-TestService 'scheduler'
    $saveTimer = [Diagnostics.Stopwatch]::StartNew()
    $syncJob = New-TestJob 'demoSuccess'
    $saveTimer.Stop()
    Assert-Check ($saveTimer.ElapsedMilliseconds -lt 2000) 'Configuration save returns while Scheduler is offline'
    $failedSync = Wait-TestSync $syncJob.id 'FAILED' 1
    Assert-Check ($failedSync.attempts -eq 3) 'Outbox stops after bounded delivery retries'
    Stop-TestService 'admin'; Start-TestService 'admin'
    $restoredSync = Wait-TestSync $syncJob.id 'FAILED' 1
    Assert-Check ($restoredSync.eventId -eq $failedSync.eventId) 'Admin restart preserves failed Outbox event and retry identity'
    Start-TestService 'scheduler'
    $retrySync = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/job-sync/updateRetry' @{ id = $syncJob.id; configVersion = 1 }
    Assert-Check ($retrySync.status -eq 200) 'Failed latest configuration can be retried explicitly'
    $firstSync = Wait-TestSync $syncJob.id 'SYNCED' 1
    $firstPlan = Call-Api 'GET' ("http://127.0.0.1:19081/internal/v1/plans/selectByJobId?id=" + $syncJob.id) $null $controlToken
    Assert-Check ($firstPlan.status -eq 200 -and $firstPlan.body.configVersion -eq 1) 'Admin to Scheduler real HTTP sync stores the versioned replica'
    $enableSync = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/jobs/enable' @{ id = $syncJob.id; configVersion = 1 }
    $null = Wait-TestSync $syncJob.id 'SYNCED' 2
    $enabledPlan = Call-Api 'GET' ("http://127.0.0.1:19081/internal/v1/plans/selectByJobId?id=" + $syncJob.id) $null $controlToken
    Assert-Check ($enableSync.status -eq 200 -and $enabledPlan.body.enabled) 'Enable mutation reaches Scheduler asynchronously'
    $disableSync = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/jobs/disable' @{ id = $syncJob.id; configVersion = 2 }
    $null = Wait-TestSync $syncJob.id 'SYNCED' 3
    $disabledPlan = Call-Api 'GET' ("http://127.0.0.1:19081/internal/v1/plans/selectByJobId?id=" + $syncJob.id) $null $controlToken
    Assert-Check ($disableSync.status -eq 200 -and !$disabledPlan.body.enabled) 'Disable mutation reaches Scheduler asynchronously'
    $deleteSync = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/jobs/delete' @{ id = $syncJob.id; configVersion = 3 }
    $deletedSync = Wait-TestSync $syncJob.id 'SYNCED' 4
    $deletedPlan = Call-Api 'GET' ("http://127.0.0.1:19081/internal/v1/plans/selectByJobId?id=" + $syncJob.id) $null $controlToken
    Assert-Check ($deleteSync.status -eq 200 -and $deletedSync.deleted -and $deletedPlan.body.deleted -and !$deletedPlan.body.enabled) 'Delete tombstone is synchronized and remains queryable'
    $oldEvent = @{ eventId = $firstSync.eventId; jobId = $syncJob.id; configVersion = 1; deleted = $false; configuration = ($firstPlan.body.snapshotJson | ConvertFrom-Json) }
    $late = Call-Api 'POST' 'http://127.0.0.1:19081/internal/v1/plans/update' $oldEvent $controlToken
    Assert-Check ($late.status -eq 200 -and $late.body.acceptedVersion -eq 4) 'Late duplicate acknowledges the latest version without resurrecting the deleted plan'
    Stop-TestService 'scheduler'; Start-TestService 'scheduler'
    $restoredPlan = Call-Api 'GET' ("http://127.0.0.1:19081/internal/v1/plans/selectByJobId?id=" + $syncJob.id) $null $controlToken
    Assert-Check ($restoredPlan.body.deleted -and $restoredPlan.body.configVersion -eq 4) 'Scheduler restart preserves replica and tombstone'

    if ($IncludeConfigReconciliation) {
        # 故障注入只修改本次脚本创建并已停止的 Scheduler H2；连接参数从隔离 YAML 读取。
        Stop-TestService 'scheduler'
        $schedulerYaml = Get-Content -LiteralPath $serviceFiles.scheduler.yaml -Raw
        $dbUrl = [regex]::Match($schedulerYaml, '(?m)^    url: "(jdbc:h2:file:[^"]+)"').Groups[1].Value
        $dbUser = [regex]::Match($schedulerYaml, '(?m)^    username: ([^\r\n]+)').Groups[1].Value
        $dbPassword = [regex]::Match($schedulerYaml, '(?m)^    password: "([^"]*)"').Groups[1].Value
        $expectedPrefix = 'jdbc:h2:file:' + $runRoot.Replace('\','/') + '/scheduler-db;'
        if (!$dbUrl.StartsWith($expectedPrefix) -or !$dbUser -or $syncJob.id -lt 1) { throw 'Reconciliation fault database guard failed.' }
        & $javaExe -cp $h2Jar.FullName org.h2.tools.Shell -url $dbUrl -user $dbUser -password $dbPassword -sql ("DELETE FROM schedule_plan WHERE job_id=" + [long]$syncJob.id)
        if ($LASTEXITCODE -ne 0) { throw 'Replica fault injection failed.' }
        Start-TestService 'scheduler'
        $historical = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/job-sync/selectByJobId?id=" + $syncJob.id)
        Assert-Check ($historical.body.data.state -eq 'SYNCED') 'Historical sync remains successful after Scheduler replica loss'
        $staleRestore = Call-Api 'POST' 'http://127.0.0.1:19081/internal/v1/plans/update' $oldEvent $controlToken
        Assert-Check ($staleRestore.status -eq 409 -and $staleRestore.body.code -eq 'PLAN_RESTORE_REQUIRES_LATEST') 'Surviving highest Inbox version blocks stale re-enable while replica is missing'
        $scanRequest = @{ requestId = [Guid]::NewGuid().ToString(); afterJobId = [long]$syncJob.id - 1; batchSize = 1 }
        $scan = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/config-reconciliation/insert' $scanRequest
        Assert-Check ($scan.status -eq 200 -and $scan.body.data.checks[0].difference -eq 'MISSING' -and $scan.body.data.checks[0].source.deleted) 'Real version query finds missing deletion tombstone despite historical confirmation'
        $scanAgain = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/config-reconciliation/insert' $scanRequest
        Assert-Check ($scanAgain.status -eq 200 -and $scanAgain.body.data.checks[0].id -eq $scan.body.data.checks[0].id) 'Scan request replay returns persisted observation'
        $repairRequest = @{ requestId = [Guid]::NewGuid().ToString(); checkId = $scan.body.data.checks[0].id }
        $repair = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/config-reconciliation/updateRepair' $repairRequest
        Assert-Check ($repair.status -eq 200 -and $repair.body.data.outcome -eq 'ENQUEUED') 'Controlled repair queues original event through Admin Outbox'
        $repairedSync = Wait-TestSync $syncJob.id 'SYNCED' 4
        $repairedPlan = Call-Api 'GET' ("http://127.0.0.1:19081/internal/v1/plans/selectByJobId?id=" + $syncJob.id) $null $controlToken
        Assert-Check ($repairedPlan.body.deleted -and !$repairedPlan.body.enabled -and $repairedPlan.body.configVersion -eq 4 -and $repairedSync.eventId -eq $deletedSync.eventId) 'Surviving Inbox allows original event to rebuild tombstone without re-enabling Job'
        Stop-TestService 'admin'; Start-TestService 'admin'
        $audit = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/config-reconciliation/selectRepairById?id=" + $repairRequest.requestId)
        $repairAgain = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/config-reconciliation/updateRepair' $repairRequest
        Assert-Check ($audit.body.data.outcome -eq 'ENQUEUED' -and $repairAgain.body.data.createdAt -eq $repair.body.data.createdAt) 'Restart preserves repair audit and idempotency'
    }

    $cronCreated = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/jobs/insert' @{ name = 'plan-calculation-e2e'; handlerKey = 'demoSuccess';
        scheduleType = 'CRON'; cronExpression = '0 0 9 * * *'; timeZone = 'Asia/Shanghai'; enabled = $true }
    Assert-Check ($cronCreated.status -eq 201) 'Enabled Cron configuration can be saved'
    $cronJob = $cronCreated.body.data
    $null = Wait-TestSync $cronJob.id 'SYNCED' 1
    $calculatedPlan = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/job-sync/selectPlanByJobId?id=" + $cronJob.id)
    Assert-Check ($calculatedPlan.status -eq 200 -and $calculatedPlan.body.data.calculationState -eq 'READY' -and $calculatedPlan.body.data.nextFireTime) 'Admin queries persisted Cron time from Scheduler'
    $preview = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/jobs/preview' @{ cronExpression = '0 0 9 * * *'; timeZone = 'Asia/Shanghai'; from = $calculatedPlan.body.data.calculatedAt; count = 1 }
    Assert-Check ($preview.body.data.nextFireTimes[0] -eq $calculatedPlan.body.data.nextFireTime) 'Persisted plan agrees with Cron and IANA time zone'
    Stop-TestService 'scheduler'; Start-TestService 'scheduler'
    $persistedPlan = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/job-sync/selectPlanByJobId?id=" + $cronJob.id)
    Assert-Check ($persistedPlan.body.data.nextFireTime -eq $calculatedPlan.body.data.nextFireTime -and $persistedPlan.body.data.calculatedAt -eq $calculatedPlan.body.data.calculatedAt) 'Restart and queries preserve the previously calculated time'
    $pausedCron = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/jobs/disable' @{ id = $cronJob.id; configVersion = 1 }
    $null = Wait-TestSync $cronJob.id 'SYNCED' 2
    $pausedPlan = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/job-sync/selectPlanByJobId?id=" + $cronJob.id)
    Assert-Check ($pausedCron.status -eq 200 -and $pausedPlan.body.data.calculationState -eq 'DISABLED' -and !$pausedPlan.body.data.nextFireTime) 'Disabling Cron clears its persisted next time'
    $cronHistory = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/runs/select?jobId=" + $cronJob.id)
    Assert-Check ($cronHistory.body.data.total -eq 0) 'Future daily plan does not fire before its scheduled time'

    $automatic = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/jobs/insert' @{ name = 'automatic-cron-e2e'; handlerKey = 'demoSuccess';
        scheduleType = 'CRON'; cronExpression = '*/2 * * * * *'; timeZone = 'UTC'; enabled = $true; misfirePolicy = 'FIRE_ONCE' }
    Assert-Check ($automatic.status -eq 201) 'Frequent Cron configuration is accepted'
    $automaticJob = $automatic.body.data
    $null = Wait-TestSync $automaticJob.id 'SYNCED' 1
    $automaticHistory = $null
    for ($i = 0; $i -lt 60; $i++) {
        $history = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/runs/select?jobId=" + $automaticJob.id)
        $automaticHistory = @($history.body.data.records | Where-Object { $_.triggerType -eq 'CRON' -and $_.state -eq 'SUCCEEDED' })
        if ($automaticHistory.Count -gt 0) { break }
        Start-Sleep -Milliseconds 250
    }
    Assert-Check ($automaticHistory.Count -gt 0 -and $automaticHistory[0].scheduledAt) 'Due scan creates Cron Run and Worker completes it without manual trigger'
    $automaticDetails = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/runs/selectById?id=" + $automaticHistory[0].id)
    Assert-Check (@($automaticDetails.body.data.attempts).Count -eq 1 -and $automaticDetails.body.data.snapshot.configVersion -eq 1) 'Automatic Run uses persisted snapshot and first Attempt'
    $null = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/jobs/disable' @{ id = $automaticJob.id; configVersion = 1 }
    $null = Wait-TestSync $automaticJob.id 'SYNCED' 2
    $beforePause = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/runs/select?jobId=" + $automaticJob.id)
    Start-Sleep -Milliseconds 2500
    $afterPause = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/runs/select?jobId=" + $automaticJob.id)
    Assert-Check ($beforePause.body.data.total -eq $afterPause.body.data.total) 'Disable synchronized to Scheduler prevents future automatic Runs'

    $beforeWorkerRestart=Wait-TestNode 'ONLINE'
    Stop-TestService 'worker'
    $offlineNode=Wait-TestNode 'OFFLINE'
    Assert-Check (!$offlineNode.dispatchEligible -and $offlineNode.generation -eq $beforeWorkerRestart.generation) 'Heartbeat expiry marks stopped Worker offline without losing its generation'
    $blockedJob=New-TestJob 'demoSuccess'
    $blockedRun=New-TestRun $blockedJob
    Start-Sleep -Milliseconds 1000
    $blocked=Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/runs/selectById?id=" + $blockedRun.run.id)
    Assert-Check ($blocked.body.data.state -eq 'READY' -and !$blocked.body.data.attempts[0].expiresAt -and $blocked.body.data.attempts[0].dispatchCount -eq 0) 'Offline Worker blocks new dispatch without consuming execution lease or retry budget'
    Start-TestService 'worker'
    $reconnectedNode=Wait-TestNode 'ONLINE'
    Assert-Check ($reconnectedNode.generation -eq $beforeWorkerRestart.generation + 1) 'Worker process restart registers a new fenced session'
    $unblocked=Wait-TestRun $blockedRun.run.id
    Assert-Check ($unblocked.state -eq 'SUCCEEDED' -and @($unblocked.attempts).Count -eq 1) 'Waiting Attempt resumes after Worker registration without creating a second Attempt'
    Stop-TestService 'scheduler'
    $nodeOutage=Call-Api 'GET' 'http://127.0.0.1:19080/api/v1/workers/select'
    Assert-Check ($nodeOutage.status -eq 503) 'Admin node query reports Scheduler outage instead of inventing an empty list'
    Start-TestService 'scheduler'
    $restoredNode=Wait-TestNode 'ONLINE'
    Assert-Check ($restoredNode.generation -eq $reconnectedNode.generation) 'Scheduler restart retains node generation and accepts the existing Worker session'

    if ($IncludeWorkerCluster) {
        $secondYaml = Join-Path $runRoot 'worker2.yml'
        $secondConfiguration = [IO.File]::ReadAllText($serviceFiles['worker'].yaml).
            Replace('worker-db', 'worker2-db').Replace('  port: 19082', '  port: 19083').
            Replace('    worker-id: worker-default', '    worker-id: worker-second').
            Replace('    transport: rabbitmq', "    transport: rabbitmq`n    queue: taskflow.worker.second.v1`n    routing-key: worker.second`n    dead-letter-queue: taskflow.worker.second.dead.v1`n    dead-letter-routing-key: worker.second.dead")
        [IO.File]::WriteAllText($secondYaml, $secondConfiguration, $utf8)
        $secondJar = Join-Path $runRoot 'taskflow-worker2.jar'
        Copy-Item -LiteralPath $serviceFiles['worker'].jar -Destination $secondJar
        $serviceFiles['worker2'] = @{jar=$secondJar;yaml=$secondYaml}
        Start-TestService 'worker2'; $null = Wait-TestNode 'ONLINE' 'worker-second'
        $clusterJob = New-TestJob 'demoSlow' 2 15 2000
        $clusterRuns = @(1..8 | ForEach-Object { New-TestRun $clusterJob })
        $completedClusterRuns = @($clusterRuns | ForEach-Object { Wait-TestRun $_.run.id })
        $owners = @($completedClusterRuns | ForEach-Object { $_.attempts[0].targetWorkerId } | Sort-Object -Unique)
        Assert-Check ($owners.Count -eq 2 -and @($completedClusterRuns | Where-Object state -ne 'SUCCEEDED').Count -eq 0) 'Two Worker processes with separate queues and Inbox databases both execute assigned runs'
        foreach ($completedRun in $completedClusterRuns) {
            $attempt = $completedRun.attempts[0]
            $ownerPort = if ($attempt.targetWorkerId -eq 'worker-default') { 19082 } else { 19083 }
            $otherPort = if ($ownerPort -eq 19082) { 19083 } else { 19082 }
            $local = Call-Api 'GET' ("http://127.0.0.1:$ownerPort/internal/v1/executions/selectById?attemptId=" + $attempt.id) $null $dispatchToken
            $other = Call-Api 'GET' ("http://127.0.0.1:$otherPort/internal/v1/executions/selectById?attemptId=" + $attempt.id) $null $dispatchToken
            Assert-Check ($local.status -eq 200 -and $local.body.state -eq 'SUCCEEDED' -and $other.status -eq 404) 'Attempt is present only in its selected Worker Inbox'
        }
        $failoverJob = New-TestJob 'demoSlow' 2 15 4000
        $failoverRun = New-TestRun $failoverJob
        $running = $null
        for ($i=0; $i -lt 60; $i++) {
            $candidate = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/runs/selectById?id=" + $failoverRun.run.id)
            if ($candidate.body.data.attempts[0].ownerSessionId) { $running = $candidate.body.data; break }
            Start-Sleep -Milliseconds 100
        }
        if (!$running) { throw 'Worker did not acquire the failover test Attempt.' }
        $old = $running.attempts[0]
        $victim = if ($old.targetWorkerId -eq 'worker-default') { 'worker' } else { 'worker2' }
        Stop-TestService $victim
        $failedOver = Wait-TestRun $failoverRun.run.id
        Assert-Check ($failedOver.state -eq 'SUCCEEDED' -and @($failedOver.attempts).Count -eq 2 -and
            $failedOver.attempts[0].state -eq 'TIMED_OUT' -and $failedOver.attempts[1].targetWorkerId -ne $old.targetWorkerId) 'Expired execution lease creates a new Attempt on the surviving Worker within the same Run'
        $late = Call-Api 'POST' 'http://127.0.0.1:19081/internal/v1/attempts/updateResult' @{
            attemptId=$old.id;runId=$running.id;leaseVersion=$old.leaseVersion;status='SUCCEEDED';resultCode='SUCCEEDED';
            message='late old owner';retryable=$false;workerId=$old.ownerWorkerId;sessionId=$old.ownerSessionId } $reportToken
        Assert-Check ($late.status -eq 409) 'Old Worker result cannot overwrite the replacement Attempt'
        Start-TestService $victim
        $null = Wait-TestNode 'ONLINE' $old.targetWorkerId
    }

    if ($IncludeAttemptTimeline) {
        $crashJob = New-TestJob 'demoSlow' 2 20 8000
        $crash = New-TestRun $crashJob
        $crashRunId = $crash.run.id
        $firstAttemptId = $crash.run.attempts[0].id
        $beforeExit = Wait-TestAttemptEvents $crashRunId $firstAttemptId {
            param($timeline)
            $timeline.worker.events.eventType -contains 'HANDLER_STARTED'
        }
        Assert-Check ($beforeExit.worker.events.eventType -notcontains 'HANDLER_FINISHED') 'Worker exit is injected after Handler entry and before completion'
        Stop-TestService 'worker'
        $offline = Wait-TestAttemptEvents $crashRunId $firstAttemptId {
            param($timeline)
            $timeline.workerStatus -eq 'UNAVAILABLE' -and
                ($timeline.scheduler.events | Where-Object { $_.eventType -eq 'ATTEMPT_FINISHED' -and $_.resultCode -eq 'LEASE_EXPIRED' })
        }
        Assert-Check ($offline.worker.events.Count -eq 0 -and $offline.workerId -eq $beforeExit.workerId) 'Scheduler shows lease expiry and original target while the killed Worker is offline'
        Start-TestService 'worker'
        $final = Wait-TestRun $crashRunId
        Assert-Check ($final.state -eq 'SUCCEEDED' -and @($final.attempts).Count -eq 2 -and
            $final.attempts[0].id -eq $firstAttemptId -and $final.attempts[0].state -eq 'TIMED_OUT' -and
            $final.attempts[1].state -eq 'SUCCEEDED') 'Original Run succeeds on its second Attempt after Worker restart'
        $old = Wait-TestAttemptEvents $crashRunId $firstAttemptId {
            param($timeline)
            $timeline.worker.events.eventType -contains 'EXECUTION_RECOVERED' -and
                $timeline.worker.events.eventType -contains 'REPORT_STALE'
        }
        Assert-Check ($old.scheduler.events.resultCode -contains 'LEASE_EXPIRED') 'Recovered Worker report is stale and leaves the authoritative expiry intact'
        $second = Wait-TestAttemptEvents $crashRunId $final.attempts[1].id {
            param($timeline)
            $timeline.worker.events.eventType -contains 'HANDLER_FINISHED' -and
                $timeline.worker.events.eventType -contains 'REPORT_ACKNOWLEDGED'
        }
        Assert-Check ($second.workerId -eq $final.attempts[1].targetWorkerId) 'Replacement Attempt timeline identifies the Worker and completed Handler'
        @{ retryRunId = $retried.id; crashRunId = $crashRunId; firstAttemptId = $firstAttemptId;
            secondAttemptId = $final.attempts[1].id; firstWorkerId = $old.workerId;
            secondWorkerId = $second.workerId; offline = $offline } | ConvertTo-Json -Depth 12 |
            Set-Content -LiteralPath (Join-Path $runRoot 'attempt-timeline-cases.json') -Encoding utf8
    }

    # 只清理这个隔离 Admin 创建的任务配置，运行历史保留供重启检查。
    $testJobs = Call-Api 'GET' 'http://127.0.0.1:19080/api/v1/jobs/select?pageSize=100'
    foreach ($job in $testJobs.body.data.records) {
        $deleted = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/jobs/delete' @{ id = $job.id; configVersion = $job.configVersion }
        if ($deleted.status -ne 200) { throw 'Isolated test Job cleanup failed.' }
    }

    foreach ($dependency in @('rate-redis','session-redis')) {
        $faultJob = New-TestJob 'demoSuccess'
        $null = Wait-TestSync $faultJob.id 'SYNCED' 1
        $beforeFault = Call-Api 'GET' ("http://127.0.0.1:19081/internal/v1/runs/select?jobId=" + $faultJob.id) $null $controlToken
        Stop-AcceptanceDependency $dependency
        try {
            & node (Join-Path $PSScriptRoot 'local/execution-security.mjs') $dependency $accessSettingsPath $accessTokensPath $runRoot $faultJob.id
            if ($LASTEXITCODE -ne 0) { throw "$dependency outage acceptance failed." }
        } finally { Start-AcceptanceRedis $dependency }
        # Gateway circuit breakers can remain open briefly after the fault has recovered.
        Start-Sleep -Seconds 11
        if ($dependency -eq 'session-redis') { Connect-AcceptanceSession }
        $afterFault = Call-Api 'GET' ("http://127.0.0.1:19081/internal/v1/runs/select?jobId=" + $faultJob.id) $null $controlToken
        Assert-Check ($beforeFault.status -eq 200 -and $afterFault.status -eq 200 -and $beforeFault.body.total -eq 0 -and $afterFault.body.total -eq 0) "$dependency outage rejects a valid Run command without reaching Scheduler"
        $healthAfterFault = Call-Api 'GET' 'http://127.0.0.1:19080/api/v1/jobs/selectSummary'
        Assert-Check ($healthAfterFault.status -eq 200) "$dependency recovery restores authenticated API access"
        $afterFaultRun = New-TestRun $faultJob
        Assert-Check ((Wait-TestRun $afterFaultRun.run.id).state -eq 'SUCCEEDED') "$dependency recovery restores MQ execution with a new explicit request"
        $null = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/jobs/delete' @{id=$faultJob.id; configVersion=$faultJob.configVersion}
    }

    if (!$SkipBrowser) {
        Push-Location (Join-Path $repoPath 'taskflow-web')
        try { & npm.cmd test; if ($LASTEXITCODE -ne 0) { throw 'Frontend unit tests failed.' }; & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' } }
        finally { Pop-Location }
        $nginxExe = Join-Path $repoPath '.tools/nginx-1.31.8/nginx.exe'
        if (!(Test-Path -LiteralPath $nginxExe)) { throw 'Prepare Nginx using tools/package-frontend.ps1.' }
        New-Item -ItemType Directory -Path (Join-Path $runRoot 'conf'),(Join-Path $runRoot 'logs'),(Join-Path $runRoot 'temp') -Force | Out-Null
        $nginxPrefix = $runRoot.Replace('\','/') + '/'
        $configuration = Get-Content -LiteralPath deploy/nginx/nginx.conf.template -Raw
        $configuration = $configuration.Replace('__HTTP_PORT__', "$nginxPort").Replace('__GATEWAY_HOST__','127.0.0.1').Replace('__GATEWAY_PORT__', '19088').Replace('root html;', 'root "' + (Join-Path $repoPath 'taskflow-web/dist').Replace('\','/') + '";')
        [IO.File]::WriteAllText((Join-Path $runRoot 'conf/nginx.conf'), $configuration, $utf8)
        Copy-Item -LiteralPath .tools/nginx-1.31.8/conf/mime.types -Destination (Join-Path $runRoot 'conf/mime.types')
        & $nginxExe -p $nginxPrefix -c conf/nginx.conf -t
        if ($LASTEXITCODE -ne 0) { throw 'Isolated Nginx configuration invalid.' }
        Start-Process -FilePath $nginxExe -ArgumentList @('-p', ('"' + $nginxPrefix + '"'), '-c', 'conf/nginx.conf') -WorkingDirectory $repoPath -WindowStyle Hidden | Out-Null
        $nginxStarted = $true
        Start-Sleep -Milliseconds 500
        $env:TASKFLOW_EXECUTION_E2E = 'true'
        $env:TASKFLOW_EXECUTION_EVIDENCE_DIR = $runRoot
        Push-Location (Join-Path $repoPath 'taskflow-web')
        try {
            & npm.cmd run test:e2e
            if ($LASTEXITCODE -ne 0) { throw 'Browser execution verification failed.' }
            & npx.cmd playwright test --config playwright.access.config.js
            if ($LASTEXITCODE -ne 0) { throw 'Browser login and session verification failed.' }
        }
        finally { Pop-Location }
        Assert-Check $true 'Existing console and new execution browser tests pass'
    }
    $evidence | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $runRoot 'report.json') -Encoding utf8
    Write-Output ("Execution evidence: " + $runRoot)
} finally {
    if ($nginxStarted) { & $nginxExe -p $nginxPrefix -c conf/nginx.conf -s quit }
    foreach ($name in @('gateway','admin','worker2','worker','scheduler')) { Stop-TestService $name }
    foreach ($name in @('identity','rate-redis','session-redis')) { Stop-AcceptanceDependency $name }
    foreach ($entry in $mutatedEnvironment.GetEnumerator()) {
        if ($null -eq $entry.Value) { Remove-Item ('Env:' + $entry.Key) -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
    }
    $http.Dispose(); $rng.Dispose()
}

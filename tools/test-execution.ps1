param([switch]$SkipBuild, [switch]$SkipBrowser, [switch]$InjectWorkerResultSaveFailure,
    [ValidateSet('http','rabbitmq')][string]$Transport = 'http', [string]$MqSettingsPath,[string]$PlatformSettingsPath)
$ErrorActionPreference = 'Stop'
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
    $processes[$Name] = [pscustomobject]@{ id = $process.Id; jar = $file.jar; log = $out }
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
    $process = Get-CimInstance Win32_Process -Filter ("ProcessId = " + $entry.id)
    if ($process) {
        if (!$process.CommandLine.Contains($entry.jar)) { throw "Process identity mismatch for $Name" }
        Stop-Process -Id $entry.id -ErrorAction Stop
        Wait-Process -Id $entry.id -Timeout 10 -ErrorAction SilentlyContinue
    }
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
function Wait-TestSync([long]$JobId, [string]$State, [long]$Version) {
    for ($i = 0; $i -lt 80; $i++) {
        $result = Call-Api 'GET' ("http://127.0.0.1:19080/api/v1/job-sync/selectByJobId?id=" + $JobId)
        if ($result.status -eq 200 -and $result.body.data.state -eq $State -and $result.body.data.configVersion -eq $Version) { return $result.body.data }
        Start-Sleep -Milliseconds 250
    }
    throw "Job $JobId sync did not reach $State version $Version."
}
function Wait-TestNode([string]$State) {
    for ($i=0; $i -lt 80; $i++) {
        $response=Call-Api 'GET' 'http://127.0.0.1:19080/api/v1/workers/selectById?workerId=worker-default'
        if ($response.status -eq 200 -and $response.body.data.status -eq $State) { return $response.body.data }
        Start-Sleep -Milliseconds 250
    }
    throw "Worker did not become $State."
}
try {
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
            $mqYaml = "  rabbitmq:`n    addresses: $($mqSettings.addresses | ConvertTo-Json -Compress)`n    virtual-host: $($mqSettings.virtualHost | ConvertTo-Json -Compress)`n    username: $($mqSettings.username | ConvertTo-Json -Compress)`n    password: $($mqSettings.password | ConvertTo-Json -Compress)"
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
    $stale = Call-Api 'POST' 'http://127.0.0.1:19081/internal/v1/attempts/updateResult' @{ attemptId = $attempt.id; runId = $success.run.id; leaseVersion = 999; status = 'SUCCEEDED'; resultCode = 'SUCCEEDED'; message = 'late'; retryable = $false } $reportToken
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

    # 只清理这个隔离 Admin 创建的任务配置，运行历史保留供重启检查。
    $testJobs = Call-Api 'GET' 'http://127.0.0.1:19080/api/v1/jobs/select?pageSize=100'
    foreach ($job in $testJobs.body.data.records) {
        $deleted = Call-Api 'POST' 'http://127.0.0.1:19080/api/v1/jobs/delete' @{ id = $job.id; configVersion = $job.configVersion }
        if ($deleted.status -ne 200) { throw 'Isolated test Job cleanup failed.' }
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
        try { & npm.cmd run test:e2e; if ($LASTEXITCODE -ne 0) { throw 'Browser execution verification failed.' } }
        finally { Pop-Location }
        Assert-Check $true 'Existing console and new execution browser tests pass'
    }
    $evidence | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $runRoot 'report.json') -Encoding utf8
    Write-Output ("Execution evidence: " + $runRoot)
} finally {
    if ($nginxStarted) { & $nginxExe -p $nginxPrefix -c conf/nginx.conf -s quit }
    foreach ($name in @('gateway','admin','worker','scheduler')) { Stop-TestService $name }
    foreach ($entry in $mutatedEnvironment.GetEnumerator()) {
        if ($null -eq $entry.Value) { Remove-Item ('Env:' + $entry.Key) -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process') }
    }
    $http.Dispose(); $rng.Dispose()
}

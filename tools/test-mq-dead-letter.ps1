param([Parameter(Mandatory=$true)][string]$ClusterSettingsPath, [switch]$AllowLegacyBroker)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib/mq-management.ps1')
$settings = Read-MqClusterSettings $ClusterSettingsPath
$client = New-MqManagementClient $settings.username $settings.password
$endpoint = $settings.managementUrls[0]
$vhost = 'taskflow_it_' + [Guid]::NewGuid().ToString('N')
$source = 'taskflow.worker.default.v1'
$target = 'taskflow.worker.default.dead.v1'
$runPath = Join-Path $repoPath ".tools/mq-dead-letter/$vhost"
$created = $false
$complete = $false
$collector = $null
$collectorUser = $null
$collectorUserCreated = $false
$savedUser = $env:TASKFLOW_P02_PROBE_USER
$savedPassword = $env:TASKFLOW_P02_PROBE_PASSWORD

function Broker([string]$Method,[string]$Path,$Body = $null) {
    Invoke-MqManagement $client $endpoint $Method $Path $Body
}
try {
    $capability = Get-MqBrokerCapability $client $endpoint ([bool]$AllowLegacyBroker)
    if (!$capability.legacy -and [version]([regex]::Match($capability.version,'^\d+\.\d+\.\d+').Value) -lt [version]'4.3.0') {
        throw 'This fault acceptance requires an isolated RabbitMQ 4.3+ cluster.'
    }
    if (@(Broker GET 'nodes' | Where-Object running).Count -ne 3) { throw 'Three online test nodes are required.' }
    New-Item -ItemType Directory -Path $runPath -Force | Out-Null
    $null = Broker PUT "vhosts/$vhost" @{}
    $created = $true
    $principal = [Uri]::EscapeDataString($settings.username)
    $null = Broker PUT "permissions/$vhost/$principal" @{configure='.*';write='.*';read='.*'}
    $null = Broker PUT "exchanges/$vhost/taskflow.dispatch.dead.v1" @{type='direct';durable=$true;auto_delete=$false;internal=$false;arguments=@{}}
    foreach ($queue in @($source,$target)) {
        $null = Broker PUT "queues/$vhost/$queue" @{durable=$true;auto_delete=$false;arguments=@{'x-queue-type'='quorum';'x-quorum-initial-group-size'=3}}
    }
    $null = Broker POST "bindings/$vhost/e/taskflow.dispatch.dead.v1/q/$target" @{routing_key='worker.default.dead';arguments=@{}}
    $policy = New-MqDispatchSafetyPolicy $capability.legacy
    $null = Broker PUT "policies/$vhost/source-safety" $policy
    if (!$capability.legacy) {
        $targetPolicy = New-MqDeadLetterRetentionPolicy '^taskflow\.worker\.default\.dead\.v1$'
        $targetPolicy.definition['max-length'] = 1
        $null = Broker PUT "policies/$vhost/target-reject" $targetPolicy
        Assert-MqDeadLetterPolicies $client $endpoint $vhost $source $target
    }
    $before = @(Broker GET "queues/$vhost")
    if (@($before | Where-Object { @($_.online).Count -eq 3 }).Count -ne 2) { throw 'Both queues require three online replicas.' }
    $before | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $runPath 'queues-before.json') -Encoding utf8

    $rabbit = Get-ChildItem (Join-Path $env:USERPROFILE '.m2/repository/com/rabbitmq/amqp-client/*/amqp-client-*.jar') |
        Where-Object Name -NotMatch 'sources|javadoc' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $slf4j = Get-ChildItem (Join-Path $env:USERPROFILE '.m2/repository/org/slf4j/slf4j-api/*/slf4j-api-*.jar') |
        Where-Object Name -NotMatch 'sources|javadoc' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (!$rabbit -or !$slf4j) { throw 'Run Maven verify first to populate the local dependency cache.' }
    $env:TASKFLOW_P02_PROBE_USER = $settings.username
    $env:TASKFLOW_P02_PROBE_PASSWORD = $settings.password
    $classpath = $rabbit.FullName + [IO.Path]::PathSeparator + $slf4j.FullName
    $mode = if ($capability.legacy) { 'legacy' } else { 'reliable' }
    $probe = Join-Path $PSScriptRoot 'local/DeadLetterTransferProbe.java'
    $probeLog = Join-Path $runPath 'probe.log'
    $proof = & java -cp $classpath $probe $settings.addresses $vhost "prepare-$mode" |
        Tee-Object -FilePath $probeLog
    if ($LASTEXITCODE -ne 0) { throw "Dead letter acceptance failed. Evidence: $runPath" }
    $proof | Write-Output
    $markerLine = @($proof | Where-Object { $_ -match '^MARKER [a-f0-9-]{36}$' })
    if ($markerLine.Count -ne 1) { throw 'Expected one dead letter marker.' }
    $marker = $markerLine[0].Substring(7)
    $fillersLine = @($proof | Where-Object { $_ -match '^FILLERS \d+$' })
    if ($fillersLine.Count -ne 1) { throw 'Expected a fixture count.' }
    $fillers = [int]$fillersLine[0].Substring(8)
    if (!$capability.legacy) {
        $deadline = [DateTime]::UtcNow.AddSeconds(30)
        do {
            $held = Broker GET "queues/$vhost/$source"
            $full = Broker GET "queues/$vhost/$target"
            if ($held.messages -eq 1 -and $held.messages_ready -eq 0 -and $held.messages_unacknowledged -eq 0 -and
                $full.messages_ready -eq $fillers) { break }
            Start-Sleep -Milliseconds 500
        } while ([DateTime]::UtcNow -lt $deadline)
        if ($held.messages -ne 1 -or $held.messages_ready -ne 0 -or $held.messages_unacknowledged -ne 0 -or
            $full.messages_ready -ne $fillers) { throw 'Source retained-message and full-target counts were not observed.' }
        @($held,$full) | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $runPath 'queues-blocked.json') -Encoding utf8
        Write-Output 'PASS Rejected original is retained by the source quorum while the target is full'
    }
    & java -cp $classpath $probe $settings.addresses $vhost recover $marker $fillers | Tee-Object -FilePath $probeLog -Append
    if ($LASTEXITCODE -ne 0) { throw "Dead letter recovery failed. Evidence: $runPath" }
    if (!$capability.legacy) {
        $null = Broker PUT "policies/$vhost/target-reject" (New-MqDeadLetterRetentionPolicy '^taskflow\.worker\.default\.dead\.v1$')
    }
    & java -cp $classpath $probe $settings.addresses $vhost duplicate $marker | Tee-Object -FilePath $probeLog -Append
    if ($LASTEXITCODE -ne 0) { throw "Dead letter duplicate fixture failed. Evidence: $runPath" }

    $schedulerJar = Join-Path $repoPath 'taskflow-scheduler/target/taskflow-scheduler-0.0.1-SNAPSHOT.jar'
    if (!(Test-Path -LiteralPath $schedulerJar)) { throw 'Run root Maven verify to build the Scheduler JAR first.' }
    $collectorJar = Join-Path $runPath 'scheduler.jar'
    Copy-Item -LiteralPath $schedulerJar -Destination $collectorJar
    $h2 = Get-ChildItem (Join-Path $env:USERPROFILE '.m2/repository/com/h2database/h2/*/h2-*.jar') |
        Where-Object Name -NotMatch 'sources|javadoc' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $collectorUser = 'taskflow_it_dl_' + [Guid]::NewGuid().ToString('N')
    $collectorPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
    $controlToken = [Guid]::NewGuid().ToString('N')
    $null = Broker PUT "users/$collectorUser" @{password=$collectorPassword;tags=''}
    $collectorUserCreated = $true
    $null = Broker PUT "permissions/$vhost/$collectorUser" @{configure='^$';write='^$';read='^taskflow\.worker\.default\.dead\.v1$'}
    $permissions = Broker GET "permissions/$vhost/$collectorUser"
    if ($permissions.configure -ne '^$' -or $permissions.write -ne '^$' -or $permissions.read -ne '^taskflow\.worker\.default\.dead\.v1$') {
        throw 'Collector must only be able to read the test dead-letter queue.'
    }
    $socket = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    try { $socket.Start(); $port = $socket.LocalEndpoint.Port } finally { $socket.Stop() }
    $yaml = Join-Path $runPath 'scheduler.yml'
    $database = (Join-Path $runPath 'scheduler-db').Replace('\','/')
    $jdbc = "jdbc:h2:file:$database;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE"
    [IO.File]::WriteAllText($yaml, @"
spring:
  datasource:
    url: "$jdbc"
    username: sa
    password: ""
    driver-class-name: org.h2.Driver
    hikari:
      connection-init-sql: "SET TIME ZONE 'UTC'"
management:
  health:
    rabbit:
      enabled: false
server:
  address: 127.0.0.1
  port: $port
taskflow:
  dispatch:
    transport: http
    dead-letter:
      enabled: true
      addresses: $($settings.addresses | ConvertTo-Json -Compress)
      virtual-host: $vhost
      username: $collectorUser
      password: $collectorPassword
      queues: $target
  execution:
    control-token: $controlToken
    scanning-enabled: false
eureka:
  client:
    enabled: false
"@, [Text.UTF8Encoding]::new($false))
    $java = (Get-Command java.exe -CommandType Application | Select-Object -First 1).Source
    $collector = Start-Process -FilePath $java -ArgumentList @(
        ('-Dloader.path="' + $h2.FullName + '"'), ('"-Dspring.config.additional-location=file:' + $yaml.Replace('\','/') + '"'),
        '-cp', ('"' + $collectorJar + '"'), 'org.springframework.boot.loader.launch.PropertiesLauncher',
        ('"--spring.datasource.url=' + $jdbc + '"'), '--spring.datasource.driver-class-name=org.h2.Driver',
        '--spring.datasource.username=sa', '--spring.datasource.password=', '--spring.profiles.active=local',
        '--taskflow.execution.scanning-enabled=false', '--taskflow.dispatch.transport=http',
        ('"--taskflow.dispatch.dead-letter.addresses=' + $settings.addresses + '"'),
        ('--taskflow.dispatch.dead-letter.virtual-host=' + $vhost), ('--taskflow.dispatch.dead-letter.queues=' + $target)
    ) -WorkingDirectory $runPath -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runPath 'scheduler.log') -RedirectStandardError (Join-Path $runPath 'scheduler.err.log')
    $deadline = [DateTime]::UtcNow.AddSeconds(50)
    do {
        if ($collector.HasExited) { throw "Scheduler collector exited. Evidence: $runPath" }
        $result = $null
        $uri = "http://127.0.0.1:$port/internal/v1/runs/selectDispatchFailures?eventId=$marker"
        try { $result = Invoke-RestMethod -TimeoutSec 2 -Headers @{'X-Taskflow-Token'=$controlToken} -Uri $uri } catch { }
        $queueState = Broker GET "queues/$vhost/$target"
        if ($result -and @($result.records).Count -eq 1 -and $queueState.messages_ready -eq 0 -and
            $queueState.messages_unacknowledged -eq 0 -and $queueState.message_stats.ack -ge 2) { break }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    if (!$result -or @($result.records).Count -ne 1 -or $result.records[0].reason -ne 'rejected' -or
        $queueState.message_stats.ack -lt 2 -or $queueState.messages_ready -ne 0 -or $queueState.messages_unacknowledged -ne 0) {
        throw "Scheduler did not persist one record and acknowledge both dead letters. Evidence: $runPath"
    }
    $collectorLog = Get-Content -LiteralPath (Join-Path $runPath 'scheduler.log') -Raw
    if (!$collectorLog.Contains("amqp://$collectorUser@") -or !$collectorLog.Contains("jdbc:h2:file:$database")) {
        throw 'Isolated data source or dedicated Broker principal was not used by Scheduler.'
    }
    $result | ConvertTo-Json -Depth 15 | Set-Content -LiteralPath (Join-Path $runPath 'collector-result.json') -Encoding utf8
    Write-Output 'PASS Scheduler committed one redacted diagnostic and acknowledged both identical deliveries with read-only Broker credentials'
    @(Broker GET "queues/$vhost") | ConvertTo-Json -Depth 15 |
        Set-Content -LiteralPath (Join-Path $runPath 'queues-after.json') -Encoding utf8
    $complete = $true
    Write-Output "Dead letter $mode acceptance passed. Evidence: $runPath"
} finally {
    $env:TASKFLOW_P02_PROBE_USER = $savedUser
    $env:TASKFLOW_P02_PROBE_PASSWORD = $savedPassword
    try {
        if ($collector) {
            if (!$collector.HasExited) { $collector.Kill(); $collector.WaitForExit(10000) | Out-Null }
            $collector.Dispose()
        }
        if ($created -and $complete) { $null = Broker DELETE "vhosts/$vhost" }
        if ($created -and !$complete) { Write-Warning "Retained failed isolated vhost $vhost for investigation. Evidence: $runPath" }
        if ($collectorUserCreated) { $null = Broker DELETE "users/$collectorUser" }
    } finally { $client.Dispose() }
}

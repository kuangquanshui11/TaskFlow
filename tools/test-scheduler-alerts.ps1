param(
    [Parameter(Mandatory)][string]$PrometheusPath,
    [Parameter(Mandatory)][string]$AlertmanagerPath,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$prometheus = (Resolve-Path -LiteralPath $PrometheusPath).Path
$alertmanager = (Resolve-Path -LiteralPath $AlertmanagerPath).Path
$java = (Get-Command java -CommandType Application | Select-Object -First 1).Source
$node = (Get-Command node -CommandType Application | Select-Object -First 1).Source
if (!$SkipBuild) {
    Push-Location $repo
    try {
        & .\mvnw.cmd -pl 'taskflow-scheduler,taskflow-worker' verify
        if ($LASTEXITCODE -ne 0) { throw 'Scheduler verify failed.' }
    } finally { Pop-Location }
}
$h2 = Get-ChildItem (Join-Path $env:USERPROFILE '.m2/repository/com/h2database/h2/*/h2-*.jar') |
    Where-Object Name -NotMatch 'sources|javadoc' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (!$h2) { throw 'Run Maven verify first to install the H2 test dependency.' }
$root = Join-Path $repo ('.tools/scheduler-alerts/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root | Out-Null
$ports = @{}
foreach ($name in @('prometheus', 'alertmanager', 'alertmanager-peer', 'mesh', 'mesh-peer', 'webhook', 'scheduler', 'scheduler-peer', 'worker')) {
    do {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $listener.Start()
        try { $port = $listener.LocalEndpoint.Port } finally { $listener.Stop() }
    } while ($port -in $ports.Values)
    $ports[$name] = $port
}
$owned = @{}
$checks = [Collections.Generic.List[object]]::new()
$utf8 = [Text.UTF8Encoding]::new($false)
$promBase = "http://127.0.0.1:$($ports.prometheus)"
$webhookLog = Join-Path $root 'webhook.jsonl'
$schedulerDb = Join-Path $root 'scheduler-db'
$workerDb = Join-Path $root 'worker-db'
$labels = 'job="taskflow-scheduler",instance="scheduler-1"'
$overdue = "taskflow_dispatch_attempt_dispatch_overdue{$labels}"
$age = "taskflow_metrics_sample_age_seconds{$labels}"
$fresh = "taskflow:scheduler_metrics:fresh{$labels}"
$peerFresh = 'taskflow:scheduler_metrics:fresh{job="taskflow-scheduler",instance="scheduler-2"}'
$workerFresh = 'taskflow:worker_metrics:fresh{job="taskflow-worker",instance="worker-default"}'
$delayAlert = 'TaskFlowSchedulerDispatchOverdue'
$metricsAlert = 'TaskFlowSchedulerMetricsUnavailable'
$controlToken = [guid]::NewGuid().ToString('N')
$nodeToken = [guid]::NewGuid().ToString('N')
$completed = $false

function Write-Evidence([string]$File, $Value) {
    [IO.File]::WriteAllText((Join-Path $root $File), ($Value | ConvertTo-Json -Depth 20), $utf8)
}

function Wait-Check([string]$Name, [scriptblock]$Condition, [int]$Seconds = 60) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        foreach ($key in $owned.Keys) {
            if ($owned[$key].HasExited) { throw "$key exited. Inspect $root" }
        }
        if (& $Condition) {
            $checks.Add(@{name=$Name;result='PASS';time=[DateTimeOffset]::Now.ToString('o')})
            Write-Output "PASS $Name"
            return
        }
        Start-Sleep -Milliseconds 1000
    } while ([DateTime]::UtcNow -lt $deadline)
    $checks.Add(@{name=$Name;result='FAIL';time=[DateTimeOffset]::Now.ToString('o')})
    throw "Timed out: $Name. Evidence: $root"
}

function Start-Isolated([string]$Name, [string]$Executable, [string[]]$Arguments, [string]$HealthPath) {
    $owned[$Name] = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $root `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root "$Name.out.log") `
        -RedirectStandardError (Join-Path $root "$Name.err.log")
    $healthUrl = "http://127.0.0.1:$($ports[$Name])$HealthPath"
    Wait-Check "$Name is ready" {
        try {
            (Invoke-WebRequest $healthUrl -TimeoutSec 3 -UseBasicParsing).StatusCode -eq 200
        } catch { $false }
    }
}

function Get-Metric([string]$Query) {
    $response = Invoke-RestMethod "$promBase/api/v1/query?query=$([uri]::EscapeDataString($Query))" -TimeoutSec 3
    if ($response.status -ne 'success') { throw "Prometheus query failed: $Query" }
    $result = @($response.data.result)
    if ($result.Count -eq 1) { return [double]::Parse($result[0].value[1], [Globalization.CultureInfo]::InvariantCulture) }
    if ($result.Count -gt 1) { throw "Query matched multiple series: $Query" }
}

function Get-ActiveAlerts {
    (Invoke-RestMethod "$promBase/api/v1/alerts" -TimeoutSec 3).data.alerts
}

function Get-Notifications([string]$AlertName, [string]$Status) {
    if (!(Test-Path -LiteralPath $webhookLog)) { return }
    foreach ($line in (Get-Content -LiteralPath $webhookLog)) {
        try { $message = $line | ConvertFrom-Json -ErrorAction Stop } catch { continue }
        foreach ($alert in $message.alerts) {
            if ($alert.status -eq $Status -and $alert.labels.alertname -eq $AlertName) { $alert }
        }
    }
}

function Test-Firing([string]$AlertName, [int]$Count = 1) {
    @(Get-ActiveAlerts | Where-Object { $_.labels.alertname -eq $AlertName -and $_.state -eq 'firing' }).Count -eq $Count
}

function Stop-Isolated([string]$Name) {
    $process = $owned[$Name]
    if (!$process.HasExited) { $process.Kill() }
    if (!$process.WaitForExit(10000)) { throw "Owned process did not exit: $($process.Id)" }
    $owned.Remove($Name)
    $process.Dispose()
}

function Start-ServiceJar([string]$Name, [string]$Module, [string]$Settings) {
    $jar = (Resolve-Path -LiteralPath (Join-Path $repo "taskflow-$Module/target/taskflow-$Module-0.0.1-SNAPSHOT.jar")).Path
    $examplePath = Join-Path $repo "config/taskflow-$Module-alerts.example.yml"
    Start-Isolated $Name $java @('-Xmx256m', ('"-Dloader.path=' + $h2.FullName + '"'),
        ('"-Dspring.config.additional-location=file:' + $examplePath.Replace('\','/') + ',file:' + $Settings.Replace('\','/') + '"'),
        '-cp', ('"' + $jar + '"'), 'org.springframework.boot.loader.launch.PropertiesLauncher') '/actuator/health'
}

function Set-BrokerDepth([string]$Name, [int]$Depth) {
    $metric = 'rabbitmq_detailed_queue_messages{queue="taskflow.worker.default.dead.v1",vhost="taskflow"}'
    $body = if ($Depth -lt 0) { 'unavailable' } else { "$metric $Depth`n" }
    [IO.File]::WriteAllText((Join-Path $root "$Name.prom"), $body, $utf8)
}

function Get-FixtureDbUrl([string]$Db) {
    "jdbc:h2:file:$($Db.Replace('\','/'));MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE"
}

function Invoke-FixtureSql([string]$Name, [string]$Sql, [string]$Db = $schedulerDb) {
    if (!(Test-Path -LiteralPath "$Db.mv.db")) { throw 'The isolated fixture database does not exist.' }
    $sqlFile = Join-Path $root "$Name.sql"
    [IO.File]::WriteAllText($sqlFile, "SET TIME ZONE 'UTC';`n$Sql", $utf8)
    & $java -cp $h2.FullName org.h2.tools.RunScript -url "$(Get-FixtureDbUrl $Db);IFEXISTS=TRUE" -user sa -script $sqlFile
    if ($LASTEXITCODE -ne 0) { throw "Isolated fixture SQL failed: $Name" }
}

try {
    $deployment = Join-Path $repo 'deploy/observability'
    $alertSecret = Join-Path $root 'webhook-url.txt'
    [IO.File]::WriteAllText($alertSecret, "http://127.0.0.1:$($ports.webhook)/webhook", $utf8)
    $config = (Get-Content -LiteralPath (Join-Path $deployment 'alertmanager.yml') -Raw).Replace(
        '/run/secrets/alert-webhook-url', ($alertSecret.Replace('\','/') | ConvertTo-Json -Compress))
    [IO.File]::WriteAllText((Join-Path $root 'alertmanager.yml'), $config, $utf8)
    $config = (Get-Content -LiteralPath (Join-Path $deployment 'prometheus.yml') -Raw).Replace(
        'alertmanager:9093', "127.0.0.1:$($ports.alertmanager)").Replace(
        'alertmanager-peer:9093', "127.0.0.1:$($ports['alertmanager-peer'])")
    [IO.File]::WriteAllText((Join-Path $root 'prometheus.yml'), $config, $utf8)
    Copy-Item -LiteralPath (Join-Path $deployment 'scheduler-alerts.yml') -Destination $root
    $targets = Join-Path $root 'expected-targets.yml'
    Copy-Item -LiteralPath (Join-Path $deployment 'expected-targets.yml') -Destination $targets
    [IO.File]::AppendAllText($targets, @"

      - record: taskflow:metrics:expected
        expr: vector(1)
        labels:
          job: taskflow-scheduler
          instance: scheduler-2
"@, $utf8)
    $brokerTargets = foreach ($name in @('broker-a', 'broker-b')) {
        @{targets=@("127.0.0.1:$($ports.webhook)");labels=@{
            '__metrics_path__'="/$name/metrics/detailed";instance=$name;broker_cluster='isolated-broker'
        }}
        [IO.File]::WriteAllText((Join-Path $root "$name.prom"), '# RabbitMQ scrape fixture', $utf8)
    }
    [IO.File]::WriteAllText((Join-Path $root 'rabbitmq.targets.yml'), (ConvertTo-Json -InputObject @($brokerTargets) -Depth 4), $utf8)
    $appConfig = @"
spring:
  datasource:
    url: "$(Get-FixtureDbUrl $schedulerDb)"
    username: sa
    password: ""
    driver-class-name: org.h2.Driver
    hikari:
      connection-init-sql: "SET TIME ZONE 'UTC'"
server:
  address: 127.0.0.1
  port: $($ports.scheduler)
management:
  health:
    rabbit:
      enabled: false
  otlp:
    metrics:
      export:
        url: "$promBase/api/v1/otlp/v1/metrics"
taskflow:
  nodes:
    node-token: $nodeToken
  metrics:
    enabled: true
    sample-interval: 5s
  dispatch:
    transport: http
    relay-enabled: false
  plans:
    initialization-enabled: false
    trigger:
      enabled: false
  execution:
    scanning-enabled: false
    control-token: $controlToken
"@
    $yaml = Join-Path $root 'scheduler.yml'
    [IO.File]::WriteAllText($yaml, $appConfig, $utf8)
    $schedulerPeerYaml = Join-Path $root 'scheduler-peer.yml'
    # The peer uses the same settings with its own process identity and HTTP port.
    $schedulerPeerConfig = $appConfig.Replace("port: $($ports.scheduler)", "port: $($ports['scheduler-peer'])")
    $schedulerPeerConfig = $schedulerPeerConfig -replace 'management:\r?\n  health:', @'
management:
  opentelemetry:
    resource-attributes:
      "[service.instance.id]": scheduler-2
  health:
'@
    [IO.File]::WriteAllText($schedulerPeerYaml, $schedulerPeerConfig, $utf8)
    $workerYaml = Join-Path $root 'worker.yml'
    $workerConfig = @"
spring:
  datasource:
    url: "$(Get-FixtureDbUrl $workerDb)"
    username: sa
    password: ""
    driver-class-name: org.h2.Driver
    hikari:
      connection-init-sql: "SET TIME ZONE 'UTC'"
server:
  address: 127.0.0.1
  port: $($ports.worker)
management:
  health:
    rabbit:
      enabled: false
  otlp:
    metrics:
      export:
        url: "$promBase/api/v1/otlp/v1/metrics"
taskflow:
  metrics:
    enabled: true
    sample-interval: 5s
  nodes:
    node-token: $nodeToken
  dispatch:
    transport: http
  execution:
    scheduler-url: "http://127.0.0.1:$($ports.scheduler)"
    scanning-enabled: false
"@
    [IO.File]::WriteAllText($workerYaml, $workerConfig, $utf8)
    $receiver = Join-Path $PSScriptRoot 'local/alert-webhook.mjs'
    Start-Isolated webhook $node @(('"' + $receiver + '"'), "$($ports.webhook)", ('"' + $webhookLog + '"'), ('"' + $root + '"')) '/health'
    Start-Isolated alertmanager $alertmanager @('--config.file=alertmanager.yml', '--storage.path=alertmanager-data',
        "--cluster.listen-address=127.0.0.1:$($ports.mesh)", "--cluster.advertise-address=127.0.0.1:$($ports.mesh)",
        "--web.listen-address=127.0.0.1:$($ports.alertmanager)") '/-/ready'
    Start-Isolated 'alertmanager-peer' $alertmanager @('--config.file=alertmanager.yml', '--storage.path=alertmanager-peer-data',
        "--cluster.listen-address=127.0.0.1:$($ports['mesh-peer'])", "--cluster.advertise-address=127.0.0.1:$($ports['mesh-peer'])",
        "--cluster.peer=127.0.0.1:$($ports.mesh)", "--web.listen-address=127.0.0.1:$($ports['alertmanager-peer'])") '/-/ready'
    $peerApi = "http://127.0.0.1:$($ports['alertmanager-peer'])/api/v2/status"
    Wait-Check 'Both Alertmanagers joined the notification cluster' {
        @((Invoke-RestMethod $peerApi -TimeoutSec 3).cluster.peers).Count -eq 2
    }
    Start-Isolated prometheus $prometheus @('--config.file=prometheus.yml', '--storage.tsdb.path=prometheus-data',
        '--web.enable-otlp-receiver', "--web.listen-address=127.0.0.1:$($ports.prometheus)") '/-/ready'
    Start-ServiceJar scheduler scheduler $yaml
    Start-ServiceJar 'scheduler-peer' scheduler $schedulerPeerYaml
    Start-ServiceJar worker worker $workerYaml
    Wait-Check 'Both Schedulers and the Worker export fresh OTLP samples and the heartbeat is online' {
        (Get-Metric $fresh) -eq 1 -and (Get-Metric $peerFresh) -eq 1 -and (Get-Metric $workerFresh) -eq 1 -and
            (Get-Metric 'taskflow:worker_node:online{worker_id="worker-default"}') -eq 1
    } 90

    $runBody = @{requestKey=[guid]::NewGuid().ToString();snapshot=@{jobId=1;configVersion=1;name='scheduler-alert-fixture';
        handlerKey='demoSuccess';payload=@{};timeoutSeconds=30;maxAttempts=1;retryIntervalSeconds=1;
        scheduleType='MANUAL';timeZone='UTC';misfirePolicy='SKIP';enabled=$true;description=''}} | ConvertTo-Json -Depth 10
    $schedulerApi = "http://127.0.0.1:$($ports.scheduler)/internal/v1/runs"
    $headers = @{'X-Taskflow-Token'=$controlToken}
    $run = Invoke-RestMethod "$schedulerApi/insert" -Method Post -Headers $headers -ContentType 'application/json' -Body $runBody -TimeoutSec 5
    $attempt = ([guid]$run.attempts[0].id).ToString()
    $sql = foreach ($code in @('HANDLER_FAILED', 'BUSINESS_HANDLER_FAILED')) {
        $id = [guid]::NewGuid().ToString()
        "INSERT INTO run_execution(id,job_id,config_version,request_key,fingerprint,state,snapshot_json,current_attempt,created_at,updated_at,result_code,finished_at,trigger_type) VALUES('$id',9,1,'$id','fixture','FAILED','{}',1,DATEADD('DAY',-3,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP,'$code',CURRENT_TIMESTAMP,'MANUAL');"
    }
    Invoke-FixtureSql 'seed-handler-failures' ($sql -join [Environment]::NewLine)
    $finishFiltered = Invoke-RestMethod "$schedulerApi/selectGlobal?timeField=FINISHED&state=FAILED&resultCode=HANDLER_FAILED" -Headers $headers -TimeoutSec 5
    if ($finishFiltered.total -ne 1 -or $finishFiltered.records[0].jobId -ne 9 -or !$finishFiltered.records[0].finishedAt) {
        throw 'Finish-time query did not find the long-lived fixture Run.'
    }
    $fixtureId = [guid]::NewGuid().ToString()
    $claimId = [guid]::NewGuid().ToString()
    $failedId = [guid]::NewGuid().ToString()
    Invoke-FixtureSql 'seed-dispatch-failures' @"
INSERT INTO dispatch_outbox(event_id,attempt_id,state,publish_attempts,next_publish_at,claim_version,claim_until,created_at) VALUES
('$fixtureId','$fixtureId','READY',0,CURRENT_TIMESTAMP,0,NULL,CURRENT_TIMESTAMP),
('$claimId','$claimId','SENDING',1,CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
('$failedId','$failedId','FAILED',9,CURRENT_TIMESTAMP,1,NULL,CURRENT_TIMESTAMP);
INSERT INTO dispatch_dead_letter(record_id,dead_queue,reason,payload_hash,received_at)
VALUES('fixture','taskflow.worker.default.dead.v1','rejected','fixture',CURRENT_TIMESTAMP);
"@
    Invoke-FixtureSql 'seed-worker-report-failure' @"
INSERT INTO worker_execution(attempt_id,run_id,fingerprint,dispatch_json,state,retryable,reported,report_count,next_report_at,created_at,finished_at)
VALUES('$fixtureId','$fixtureId','fixture','{}','FAILED',FALSE,FALSE,99,DATEADD('HOUR',-1,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP,CURRENT_TIMESTAMP);
"@ $workerDb
    Set-BrokerDepth 'broker-a' 2
    Set-BrokerDepth 'broker-b' 2

    $businessAlert = 'TaskFlowRunBusinessFailure'
    Wait-Check 'Two result codes are grouped in one notification and link to the finish-time filter' {
        foreach ($line in Get-Content -LiteralPath $webhookLog -ErrorAction SilentlyContinue) {
            $group = $line | ConvertFrom-Json
            if ($group.status -eq 'firing' -and $group.groupLabels.environment -eq 'local' -and
                $group.groupLabels.alertname -eq $businessAlert -and @($group.alerts).Count -eq 2 -and
                @($group.alerts | Where-Object {
                    $_.annotations.console_url -like "*/runs?timeField=FINISHED&state=FAILED&resultCode=$($_.labels.result_code)" -and
                    !($_.labels.PSObject.Properties.Name -contains 'instance')
                }).Count -eq 2) { return $true }
        }
        $false
    } 90
    Set-BrokerDepth 'broker-a' 0

    $backlogAlerts = @($delayAlert, 'TaskFlowDispatchOutboxPublishOverdue', 'TaskFlowDispatchOutboxClaimExpired',
        'TaskFlowDispatchOutboxExhausted', 'TaskFlowDeadLetterUnresolved', 'TaskFlowWorkerReportExhausted', 'TaskFlowBrokerDeadLetterBacklog')
    Wait-Check 'Seven backlog conditions notify once each across duplicate sources and the peer cluster' {
        foreach ($name in $backlogAlerts) {
            if (!(Test-Firing $name) -or @(Get-Notifications $name 'firing').Count -ne 1) { return $false }
        }
        $true
    } 225
    Write-Evidence 'backlog-firing.json' (Get-ActiveAlerts)

    Stop-Isolated alertmanager
    $postpone = "UPDATE run_attempt SET next_dispatch_at=DATEADD('HOUR',1,CURRENT_TIMESTAMP) WHERE id='$attempt';"
    Invoke-FixtureSql 'clear-backlogs' @"
$postpone
UPDATE dispatch_outbox SET state='CANCELLED' WHERE event_id IN ('$fixtureId','$claimId','$failedId');
UPDATE run_execution SET finished_at=DATEADD('MINUTE',-6,CURRENT_TIMESTAMP) WHERE job_id=9;
DELETE FROM dispatch_dead_letter WHERE record_id='fixture';
"@
    Invoke-FixtureSql 'confirm-worker-report' "UPDATE worker_execution SET reported=TRUE WHERE attempt_id='$fixtureId';" $workerDb
    Set-BrokerDepth 'broker-b' 0
    Wait-Check 'The surviving Alertmanager sends one recovery per backlog and per result code' {
        foreach ($name in $backlogAlerts) {
            if (!(Test-Firing $name 0) -or @(Get-Notifications $name 'resolved').Count -ne 1) { return $false }
        }
        (Test-Firing $businessAlert 0) -and @(Get-Notifications $businessAlert 'resolved').Count -eq 2
    } 120

    Stop-Isolated worker
    Set-BrokerDepth 'broker-a' -1
    $brokerMetricsAlert = 'TaskFlowBrokerMetricsUnavailable'
    $offlineAlert = 'TaskFlowWorkerOffline'
    $workerMetricsAlert = 'TaskFlowWorkerMetricsUnavailable'
    Wait-Check 'Expired Worker heartbeat is identified and notified by the surviving Alertmanager' {
        $notice = @(Get-Notifications $offlineAlert 'firing')
        (Test-Firing $offlineAlert) -and $notice.Count -eq 1 -and $notice[0].labels.worker_id -eq 'worker-default'
    } 185
    Wait-Check 'Worker OTLP and Broker scrape interruptions each notify with the affected source' {
        (Test-Firing $workerMetricsAlert) -and (Test-Firing $brokerMetricsAlert) -and
            @(Get-Notifications $workerMetricsAlert 'firing').Count -eq 1 -and
            @(Get-Notifications $brokerMetricsAlert 'firing').Count -eq 1
    } 210
    Start-ServiceJar worker worker $workerYaml
    Set-BrokerDepth 'broker-a' 0
    Wait-Check 'Fresh metrics and a registered heartbeat send one recovery per affected source' {
        (Get-Metric $workerFresh) -eq 1 -and (Get-Metric 'taskflow:worker_node:online{worker_id="worker-default"}') -eq 1 -and
            @(Get-Notifications $offlineAlert 'resolved').Count -eq 1 -and
            @(Get-Notifications $workerMetricsAlert 'resolved').Count -eq 1 -and
            @(Get-Notifications $brokerMetricsAlert 'resolved').Count -eq 1
    } 120

    Invoke-FixtureSql 'restore-dispatch-delay' "UPDATE run_attempt SET next_dispatch_at=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP) WHERE id='$attempt';"
    Wait-Check 'Both Schedulers export a positive count before database sampling is interrupted' {
        (Get-Metric $fresh) -eq 1 -and (Get-Metric $peerFresh) -eq 1 -and (Get-Metric $overdue) -eq 1 -and
            (Get-Metric 'taskflow_dispatch_attempt_dispatch_overdue{instance="scheduler-2"}') -eq 1
    }
    Invoke-FixtureSql 'interrupt-sampling' 'ALTER TABLE worker_node RENAME TO worker_node_alert_fixture;'
    Invoke-FixtureSql 'clear-delay-while-sampling-fails' $postpone
    $clusterAlert = 'TaskFlowSchedulerClusterMetricsUnavailable'
    Wait-Check 'Stale backlog does not fire while cluster and Scheduler sampling are reported unavailable' {
        (Get-Metric $age) -gt 90 -and (Get-Metric $overdue) -eq 1 -and (Test-Firing $delayAlert 0) -and
            (Test-Firing $clusterAlert) -and (Test-Firing $metricsAlert 2) -and
            @(Get-Notifications $clusterAlert 'firing').Count -eq 1 -and
            @(Get-Notifications $metricsAlert 'firing').Count -eq 2
    } 300
    Write-Evidence 'metrics-firing.json' (Get-ActiveAlerts)

    Invoke-FixtureSql 'restore-sampling' 'ALTER TABLE worker_node_alert_fixture RENAME TO worker_node;'
    Wait-Check 'Scheduler sampling recovers without repeating earlier notifications' {
        (Get-Metric $fresh) -eq 1 -and (Get-Metric $peerFresh) -eq 1 -and (Get-Metric $workerFresh) -eq 1 -and
            (Get-Metric $overdue) -eq 0 -and (Get-Metric 'taskflow:worker_node:online{worker_id="worker-default"}') -eq 1 -and
            @(Get-ActiveAlerts).Count -eq 0 -and @(Get-Notifications $delayAlert 'firing').Count -eq 1 -and
            @(Get-Notifications $clusterAlert 'resolved').Count -eq 1 -and
            @(Get-Notifications $metricsAlert 'resolved').Count -eq 2
    } 120
    Write-Evidence 'recovered.json' @{activeAlerts=@(Get-ActiveAlerts);overdue=Get-Metric $overdue;sampleAge=Get-Metric $age}
    $completed = $true
} finally {
    try {
        foreach ($process in $owned.Values) {
            if (!$process.HasExited) {
                $process.Kill()
                if (!$process.WaitForExit(10000)) { throw "Owned process did not exit: $($process.Id)" }
            }
            $process.Dispose()
        }
    } catch {
        $completed = $false
        throw
    } finally {
        Write-Evidence 'report.json' @{date=[DateTimeOffset]::Now.ToString('o');result=$(if ($completed) {'PASS'} else {'FAIL'});checks=$checks}
        Write-Output "Scheduler alert evidence: $root"
    }
}

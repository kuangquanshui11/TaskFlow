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
        & .\mvnw.cmd -pl taskflow-scheduler verify
        if ($LASTEXITCODE -ne 0) { throw 'Scheduler verify failed.' }
    } finally { Pop-Location }
}
$h2 = Get-ChildItem (Join-Path $env:USERPROFILE '.m2/repository/com/h2database/h2/*/h2-*.jar') |
    Where-Object Name -NotMatch 'sources|javadoc' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (!$h2) { throw 'Run Maven verify first to install the H2 test dependency.' }
$root = Join-Path $repo ('.tools/scheduler-alerts/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $root | Out-Null
$ports = @{}
foreach ($name in @('prometheus', 'alertmanager', 'webhook', 'scheduler')) {
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
$db = Join-Path $root 'scheduler-db'
$dbUrl = "jdbc:h2:file:$($db.Replace('\','/'));MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE"
$labels = 'job="taskflow-scheduler",instance="scheduler-1"'
$overdue = "taskflow_dispatch_attempt_dispatch_overdue{$labels}"
$age = "taskflow_metrics_sample_age_seconds{$labels}"
$fresh = "taskflow:scheduler_metrics:fresh{$labels}"
$delayAlert = 'TaskFlowSchedulerDispatchOverdue'
$metricsAlert = 'TaskFlowSchedulerMetricsUnavailable'
$controlToken = [guid]::NewGuid().ToString('N')
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

function Test-Notification([string]$AlertName, [string]$Status) {
    if (!(Test-Path -LiteralPath $webhookLog)) { return $false }
    foreach ($line in (Get-Content -LiteralPath $webhookLog)) {
        try { $message = $line | ConvertFrom-Json -ErrorAction Stop } catch { continue }
        foreach ($alert in $message.alerts) {
            if ($alert.status -eq $Status -and $alert.labels.alertname -eq $AlertName -and
                $alert.labels.job -eq 'taskflow-scheduler' -and $alert.labels.instance -eq 'scheduler-1') { return $true }
        }
    }
    return $false
}

function Invoke-FixtureSql([string]$Name, [string]$Sql) {
    if (!(Test-Path -LiteralPath "$db.mv.db")) { throw 'The isolated fixture database does not exist.' }
    $sqlFile = Join-Path $root "$Name.sql"
    [IO.File]::WriteAllText($sqlFile, "SET TIME ZONE 'UTC';`n$Sql", $utf8)
    & $java -cp $h2.FullName org.h2.tools.RunScript -url "$dbUrl;IFEXISTS=TRUE" -user sa -script $sqlFile
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
        'alertmanager:9093', "127.0.0.1:$($ports.alertmanager)")
    [IO.File]::WriteAllText((Join-Path $root 'prometheus.yml'), $config, $utf8)
    Copy-Item -LiteralPath (Join-Path $deployment 'scheduler-alerts.yml') -Destination $root
    $appConfig = @"
spring:
  datasource:
    url: "$dbUrl"
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
    $example = Join-Path $repo 'config/taskflow-scheduler-alerts.example.yml'
    $jar = (Resolve-Path -LiteralPath (Join-Path $repo 'taskflow-scheduler/target/taskflow-scheduler-0.0.1-SNAPSHOT.jar')).Path
    $receiver = Join-Path $PSScriptRoot 'local/alert-webhook.mjs'
    Start-Isolated webhook $node @(('"' + $receiver + '"'), "$($ports.webhook)", ('"' + $webhookLog + '"')) '/health'
    Start-Isolated alertmanager $alertmanager @('--config.file=alertmanager.yml', '--storage.path=alertmanager-data',
        '--cluster.listen-address=', "--web.listen-address=127.0.0.1:$($ports.alertmanager)") '/-/ready'
    Start-Isolated prometheus $prometheus @('--config.file=prometheus.yml', '--storage.tsdb.path=prometheus-data',
        '--web.enable-otlp-receiver', "--web.listen-address=127.0.0.1:$($ports.prometheus)") '/-/ready'
    Start-Isolated scheduler $java @('-Xmx256m', ('"-Dloader.path=' + $h2.FullName + '"'),
        ('"-Dspring.config.additional-location=file:' + $example.Replace('\','/') + ',file:' + $yaml.Replace('\','/') + '"'),
        '-cp', ('"' + $jar + '"'), 'org.springframework.boot.loader.launch.PropertiesLauncher') '/actuator/health'

    Wait-Check 'Real Scheduler OTLP exports both normalized metrics with the expected job and instance' {
        (Get-Metric $fresh) -eq 1 -and (Get-Metric $overdue) -eq 0 -and @(Get-ActiveAlerts).Count -eq 0
    }
    $runBody = @{requestKey=[guid]::NewGuid().ToString();snapshot=@{jobId=1;configVersion=1;name='scheduler-alert-fixture';
        handlerKey='demoSuccess';payload=@{};timeoutSeconds=30;maxAttempts=1;retryIntervalSeconds=1;
        scheduleType='MANUAL';timeZone='UTC';misfirePolicy='SKIP';enabled=$true;description=''}} | ConvertTo-Json -Depth 10
    $run = Invoke-RestMethod "http://127.0.0.1:$($ports.scheduler)/internal/v1/runs/insert" -Method Post `
        -Headers @{'X-Taskflow-Token'=$controlToken} -ContentType 'application/json' -Body $runBody -TimeoutSec 5
    $attempt = ([guid]$run.attempts[0].id).ToString()
    Wait-Check 'A real due Attempt fires the delay rule and reaches the webhook' {
        (Get-Metric $overdue) -eq 1 -and @(Get-ActiveAlerts | Where-Object {
            $_.labels.alertname -eq $delayAlert -and $_.state -eq 'firing'
        }).Count -eq 1 -and (Test-Notification $delayAlert 'firing')
    } 195
    Write-Evidence 'dispatch-firing.json' (Get-ActiveAlerts)

    $postpone = "UPDATE run_attempt SET next_dispatch_at=DATEADD('HOUR',1,CURRENT_TIMESTAMP) WHERE id='$attempt';"
    Invoke-FixtureSql 'clear-dispatch-delay' $postpone
    Wait-Check 'Fresh zero backlog resolves the delay alert and sends recovery' {
        (Get-Metric $fresh) -eq 1 -and (Get-Metric $overdue) -eq 0 -and @(Get-ActiveAlerts).Count -eq 0 -and
            (Test-Notification $delayAlert 'resolved')
    } 90

    Invoke-FixtureSql 'restore-dispatch-delay' "UPDATE run_attempt SET next_dispatch_at=DATEADD('MINUTE',-1,CURRENT_TIMESTAMP) WHERE id='$attempt';"
    Wait-Check 'A fresh positive count is exported before sampling is interrupted' {
        (Get-Metric $fresh) -eq 1 -and (Get-Metric $overdue) -eq 1
    }
    Invoke-FixtureSql 'interrupt-sampling' 'ALTER TABLE worker_node RENAME TO worker_node_alert_fixture;'
    Invoke-FixtureSql 'clear-delay-while-sampling-fails' $postpone
    Wait-Check 'Stale retained backlog raises only the metrics alert and sends a notification' {
        $active = @(Get-ActiveAlerts)
        (Get-Metric $age) -gt 90 -and (Get-Metric $overdue) -eq 1 -and $active.Count -eq 1 -and
            $active[0].labels.alertname -eq $metricsAlert -and $active[0].state -eq 'firing' -and
            (Test-Notification $metricsAlert 'firing')
    } 270
    Write-Evidence 'metrics-firing.json' (Get-ActiveAlerts)

    Invoke-FixtureSql 'restore-sampling' 'ALTER TABLE worker_node_alert_fixture RENAME TO worker_node;'
    Wait-Check 'Restored sampling exports zero backlog and sends metrics recovery' {
        (Get-Metric $fresh) -eq 1 -and (Get-Metric $overdue) -eq 0 -and @(Get-ActiveAlerts).Count -eq 0 -and
            (Test-Notification $metricsAlert 'resolved')
    } 90
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

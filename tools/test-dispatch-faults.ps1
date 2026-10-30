param([Parameter(Mandatory)][string]$ClusterSettingsPath, [switch]$AllowLegacyBroker, [switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib/mq-management.ps1')
$cluster = Read-MqClusterSettings $ClusterSettingsPath
$java = (Get-Command java -CommandType Application | Select-Object -First 1).Source
$node = (Get-Command node -CommandType Application | Select-Object -First 1).Source
$h2 = Get-ChildItem (Join-Path $env:USERPROFILE '.m2/repository/com/h2database/h2/*/h2-*.jar') |
    Where-Object Name -NotMatch 'sources|javadoc' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (!$h2) { throw 'H2 is missing; run Maven verify first.' }
if (!$SkipBuild) {
    & (Join-Path $repoPath 'mvnw.cmd') verify
    if ($LASTEXITCODE -ne 0) { throw 'Backend verify failed.' }
}
$vhost = 'taskflow_it_' + [Guid]::NewGuid().ToString('N')
$root = Join-Path $repoPath ('.tools/dispatch-faults/' + $vhost)
$classes = Join-Path $root 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$control = [Guid]::NewGuid().ToString('N'); $report = [Guid]::NewGuid().ToString('N'); $nodeToken = [Guid]::NewGuid().ToString('N')
$processes = @{}; $files = @{}; $ports = @{}; $checks = [Collections.Generic.List[object]]::new()
$allocatedPorts = [Collections.Generic.HashSet[int]]::new()
foreach ($name in @('scheduler','worker','worker2','proxy')) {
    do {
        $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $listener.Start(); $port = $listener.LocalEndpoint.Port; $listener.Stop()
    } while (!$allocatedPorts.Add($port))
    $ports[$name] = $port
}
$http = [Net.Http.HttpClient]::new(); $http.Timeout = [TimeSpan]::FromSeconds(5)
$management = New-MqManagementClient $cluster.username $cluster.password
$base = $cluster.managementUrls[0]
$completed = $false; $created = $false; $proxy = $null
function Write-JsonFile([string]$Name, $Value) {
    [IO.File]::WriteAllText((Join-Path $root $Name), ($Value | ConvertTo-Json -Depth 60), $utf8)
}
function Check([bool]$Value, [string]$Name) {
    $checks.Add(@{name=$Name;result=$(if ($Value) {'PASS'} else {'FAIL'})})
    if (!$Value) { throw "Fault acceptance failed: $Name" }
    Write-Output "PASS $Name"
}
function Wait-Until([scriptblock]$Condition, [string]$Description, [int]$Seconds = 45) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        if (& $Condition) { return }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $Description. Evidence: $root"
}
function Broker([string]$Method, [string]$Path, $Body = $null) {
    Invoke-MqManagement $management $base $Method $Path $Body
}
function Api([string]$Method, [string]$Path, $Body = $null) {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), "http://127.0.0.1:$($ports.scheduler)/internal/v1/$Path")
    $null = $request.Headers.TryAddWithoutValidation('X-Taskflow-Token', $control)
    if ($null -ne $Body) { $request.Content = [Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 30), [Text.Encoding]::UTF8, 'application/json') }
    try {
        $response = $http.SendAsync($request).GetAwaiter().GetResult()
        try {
            if (!$response.IsSuccessStatusCode) { throw "Internal test API failed: $Method $Path HTTP $([int]$response.StatusCode)" }
            return ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}
function Read-Db([string]$Name, [string]$Sql) {
    $output = & $java '-cp' ($classes + ';' + $h2.FullName) DispatchFaultProbe (Join-Path $root ($Name + '-db')) $Sql
    if ($LASTEXITCODE -ne 0) { throw "Evidence query failed: $Name" }
    return ($output | ConvertFrom-Json)
}
function Stop-ServiceProcess([string]$Name) {
    if (!$processes.ContainsKey($Name)) { return }
    $process = $processes[$Name]
    if (!$process.HasExited) {
        $process.Kill()
        if (!$process.WaitForExit(10000)) { throw "Owned test process did not exit: $Name" }
    }
    $process.Dispose(); $processes.Remove($Name)
}
function Start-ServiceProcess([string]$Name, [bool]$Consumer = $true) {
    $file = $files[$Name]
    $stamp = Get-Date -Format 'HHmmssfff'
    $arguments = @('-Xms64m','-Xmx256m', ('"-Dloader.path=' + $classes + ',' + $h2.FullName + '"'),
        ('"-Dspring.config.additional-location=file:' + $file.yaml.Replace('\','/') + '"'),
        ('-Dtaskflow.dispatch.consumer-enabled=' + $Consumer.ToString().ToLowerInvariant()),
        '-cp', ('"' + $file.jar + '"'), 'org.springframework.boot.loader.launch.PropertiesLauncher')
    $process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $repoPath -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $root "$Name-$stamp.out.log") -RedirectStandardError (Join-Path $root "$Name-$stamp.err.log")
    $processes[$Name] = $process
    Wait-Until {
        if ($process.HasExited) { throw "$Name exited; inspect $root" }
        try {
            $response = $http.GetAsync("http://127.0.0.1:$($ports[$Name])/actuator/health").GetAwaiter().GetResult()
            try { return [int]$response.StatusCode -eq 200 } finally { $response.Dispose() }
        } catch { return $false }
    } "$Name startup" 60
    Write-Output "$Name ready (PID $($process.Id))."
}
function Wait-Node([string]$Worker, [string]$State = 'ONLINE') {
    Wait-Until {
        try { return (Api GET "workers/selectById?workerId=$Worker").status -eq $State }
        catch { if ($_.Exception.Message -match 'HTTP 404$') { return $false }; throw }
    } "$Worker $State"
}
function New-Run([string]$Handler = 'demoSuccess', [int]$Attempts = 1, [int]$Delay = 0, [int]$Timeout = 30) {
    Api POST 'runs/insert' @{requestKey=[Guid]::NewGuid().ToString();snapshot=@{
        jobId=1;configVersion=1;name='isolated-dispatch-fault';handlerKey=$Handler;payload=@{durationMillis=$Delay};
        timeoutSeconds=$Timeout;maxAttempts=$Attempts;retryIntervalSeconds=1;scheduleType='MANUAL';
        timeZone='Asia/Shanghai';misfirePolicy='SKIP';enabled=$true;description=''}}
}
function Get-Run([string]$Id) { Api GET "runs/selectById?id=$Id" }
function Wait-Terminal([string]$Id) {
    Wait-Until { (Get-Run $Id).state -in @('SUCCEEDED','FAILED') } "Run $Id terminal" 65
    Get-Run $Id
}
function Wait-Reported([string]$Worker, [string]$Attempt) {
    Wait-Until { @((Read-Db $Worker "SELECT reported FROM worker_execution WHERE attempt_id='$Attempt'") | Where-Object reported).Count -eq 1 } "Inbox $Attempt reported"
}
function Snapshot([string]$Label, [string]$Id) {
    $value = @{run=@(Read-Db scheduler "SELECT * FROM run_execution WHERE id='$Id'");
        attempts=@(Read-Db scheduler "SELECT * FROM run_attempt WHERE run_id='$Id' ORDER BY attempt_number");
        outbox=@(Read-Db scheduler "SELECT * FROM dispatch_outbox WHERE attempt_id IN (SELECT id FROM run_attempt WHERE run_id='$Id') ORDER BY created_at");
        inbox=@{}}
    foreach ($name in @('worker','worker2')) {
        if (Test-Path -LiteralPath (Join-Path $root "$name-db.mv.db")) {
            $value.inbox[$name] = @(Read-Db $name "SELECT * FROM worker_execution WHERE run_id='$Id' ORDER BY created_at")
        }
    }
    Write-JsonFile "$Label.json" $value
    return $value
}
function Count-RejectedResults {
    $log = Join-Path $root 'scheduler-access.log'
    if (!(Test-Path -LiteralPath $log)) { return 0 }
    return @(Get-Content -LiteralPath $log | Where-Object { $_ -match 'POST /internal/v1/attempts/updateResult HTTP/1\.[01] 409$' }).Count
}
try {
    $capability = Get-MqBrokerCapability $management $base ([bool]$AllowLegacyBroker)
    $null = Broker PUT "vhosts/$vhost" @{}; $created = $true
    $null = Broker PUT "permissions/$vhost/$([Uri]::EscapeDataString($cluster.username))" @{configure='.*';write='.*';read='.*'}
    foreach ($role in @('scheduler','worker')) {
        if ($cluster.serviceCredentials) {
            $credentials = $cluster.serviceCredentials.$role
            if (!$credentials.username -or !$credentials.password -or $credentials.username -eq $cluster.username) { throw 'Complete independent service roles are required.' }
            $permission = if ($role -eq 'scheduler') { @{configure='^$';write='^taskflow\.dispatch\.v1$';read='^$'} }
                else { @{configure='^$';write='^$';read='^taskflow\.worker\.(default|second)\.v1$'} }
            $null = Broker PUT "permissions/$vhost/$([Uri]::EscapeDataString($credentials.username))" $permission
        }
    }
    foreach ($exchange in @('taskflow.dispatch.v1','taskflow.dispatch.dead.v1')) {
        $null = Broker PUT "exchanges/$vhost/$exchange" @{type='direct';durable=$true;auto_delete=$false;internal=$false;arguments=@{}}
    }
    foreach ($route in @('default','second')) {
        foreach ($queue in @("taskflow.worker.$route.v1","taskflow.worker.$route.dead.v1")) {
            $null = Broker PUT "queues/$vhost/$queue" @{durable=$true;auto_delete=$false;arguments=@{'x-queue-type'='quorum';'x-quorum-initial-group-size'=3}}
        }
        $null = Broker POST "bindings/$vhost/e/taskflow.dispatch.v1/q/taskflow.worker.$route.v1" @{routing_key="worker.$route";arguments=@{}}
        $null = Broker POST "bindings/$vhost/e/taskflow.dispatch.dead.v1/q/taskflow.worker.$route.dead.v1" @{routing_key="worker.$route.dead";arguments=@{}}
        $policy = New-MqDispatchSafetyPolicy $capability.legacy
        $policy.pattern = '^taskflow\.worker\.' + $route + '\.v1$'; $policy.definition['dead-letter-routing-key'] = "worker.$route.dead"
        $null = Broker PUT "policies/$vhost/safety-$route" $policy
    }
    $topology = @(Broker GET "queues/$vhost")
    Write-JsonFile 'quorum-before.json' @($topology | Select-Object name,type,members,online)
    Check ($topology.Count -eq 4 -and @($topology | Where-Object { $_.type -ne 'quorum' -or @($_.online).Count -ne 3 }).Count -eq 0) 'Four isolated quorum queues have three online replicas'
    & (Join-Path (Split-Path -Parent $java) 'javac.exe') -cp $h2.FullName -d $classes (Join-Path $PSScriptRoot 'local/DispatchFaultProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Fault probe compilation failed.' }
    $target = [Uri]('amqp://' + $cluster.addresses.Split(',')[0])
    Write-JsonFile 'proxy.json' @{port=$ports.proxy;targetHost=$target.Host;targetPort=$target.Port}
    $proxy = Start-Process -FilePath $node -ArgumentList @(('"' + (Join-Path $PSScriptRoot 'local/mq-fault-proxy.mjs') + '"'),('"' + (Join-Path $root 'proxy.json') + '"')) `
        -WorkingDirectory $repoPath -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'proxy.out.log') -RedirectStandardError (Join-Path $root 'proxy.err.log')
    Wait-Until { if ($proxy.HasExited) { throw 'Proxy exited' }; Test-Path -LiteralPath (Join-Path $root 'proxy.ready') } 'AMQP proxy ready'
    foreach ($name in @('scheduler','worker','worker2')) {
        $role = if ($name -eq 'worker2') { 'worker' } else { $name }
        $credentials = if ($cluster.serviceCredentials) { $cluster.serviceCredentials.$role } else { $cluster }
        $route = if ($name -eq 'worker2') { 'second' } else { 'default' }
        $workerId = if ($name -eq 'worker2') { 'worker-second' } else { 'worker-default' }
        $addresses = if ($name -eq 'scheduler') { "127.0.0.1:$($ports.proxy)" } else { $cluster.addresses }
        $migrationDir = Join-Path $root "$name-migrations"
        New-Item -ItemType Directory -Path $migrationDir -Force | Out-Null
        $table = if ($name -eq 'scheduler') { 'dispatch_outbox' } else { 'worker_execution' }
        [IO.File]::WriteAllText((Join-Path $migrationDir 'R__fault_barrier.sql'), "CREATE TRIGGER fault_barrier BEFORE UPDATE ON $table FOR EACH ROW CALL `"DispatchFaultProbe`";", $utf8)
        $yaml = Join-Path $root "$name.yml"
        $configuration = @"
spring:
  config:
    import: ""
  datasource:
    url: "jdbc:h2:file:$($root.Replace('\','/'))/$name-db;MODE=MySQL;DATABASE_TO_LOWER=TRUE;AUTO_SERVER=TRUE;WRITE_DELAY=0"
    username: sa
    password: ""
    driver-class-name: org.h2.Driver
    hikari:
      connection-init-sql: "SET TIME ZONE 'UTC'"
  flyway:
    locations: "classpath:db/migration,filesystem:$($migrationDir.Replace('\','/'))"
  rabbitmq:
    addresses: $($addresses | ConvertTo-Json -Compress)
    virtual-host: $vhost
    username: $($credentials.username | ConvertTo-Json -Compress)
    password: $($credentials.password | ConvertTo-Json -Compress)
    connection-timeout: 1s
server:
  address: 127.0.0.1
  port: $($ports[$name])
  tomcat:
    accesslog:
      enabled: true
      directory: "$($root.Replace('\','/'))"
      prefix: $name-access
      suffix: .log
      rotate: false
      buffered: false
      pattern: "%r %s"
management:
  health:
    rabbit:
      enabled: false
taskflow:
  nodes:
    worker-id: $workerId
    node-token: $nodeToken
    scheduler-url: "http://127.0.0.1:$($ports.scheduler)"
    offline-timeout: 2s
    heartbeat-interval: 200ms
    request-timeout: 1s
  plans:
    trigger:
      enabled: false
  dispatch:
    transport: rabbitmq
    queue: taskflow.worker.$route.v1
    routing-key: worker.$route
    dead-letter-queue: taskflow.worker.$route.dead.v1
    dead-letter-routing-key: worker.$route.dead
    confirm-timeout: 1s
    claim-lease: 8s
    scan-interval: 200ms
    retry-delay: 4s
    max-retry-delay: 4s
    max-publish-attempts: 3
  execution:
    scheduler-url: "http://127.0.0.1:$($ports.scheduler)"
    control-token: $control
    dispatch-token: $control
    report-token: $report
    scan-interval: 200
    connect-timeout: 1s
    request-timeout: 1s
    max-report-attempts: 3
    report-retry-delay: 500ms
    lease:
      duration: 10s
      renewal-interval: 500ms
      safety-margin: 2s
"@
        [IO.File]::WriteAllText($yaml, $configuration, $utf8)
        $jar = Join-Path $root "$name.jar"
        Copy-Item -LiteralPath (Join-Path $repoPath "taskflow-$role/target/taskflow-$role-0.0.1-SNAPSHOT.jar") -Destination $jar
        $files[$name] = @{yaml=$yaml;jar=$jar}
    }

    # A confirmed message remains in the queue while the publishing process dies before its SQL commit.
    Start-ServiceProcess scheduler; Start-ServiceProcess worker $false; Wait-Node worker-default
    [IO.File]::WriteAllText((Join-Path $root 'dispatch_outbox-published.arm'), '*')
    $relayRun = New-Run -Timeout 120
    Wait-Until { Test-Path -LiteralPath (Join-Path $root 'dispatch_outbox-published.json') } 'Publisher confirm / SQL barrier'
    Stop-ServiceProcess scheduler
    $before = Snapshot 'relay-after-kill' $relayRun.id
    Wait-Until { (Broker GET "queues/$vhost/taskflow.worker.default.v1").messages_ready -eq 1 } 'Broker statistics show confirmed queued message'
    $queue = Broker GET "queues/$vhost/taskflow.worker.default.v1"
    Write-JsonFile 'relay-broker-after-kill.json' ($queue | Select-Object name,messages_ready,messages_unacknowledged,message_stats)
    Check ($before.outbox[0].state -eq 'SENDING' -and $before.outbox[0].publish_attempts -eq 1 -and $queue.messages_ready -eq 1 -and $before.inbox.worker.Count -eq 0) 'Relay killed after Broker confirmation leaves one queued message and SENDING Outbox'
    Start-ServiceProcess scheduler
    Wait-Until { @((Read-Db scheduler 'SELECT state,publish_attempts FROM dispatch_outbox') | Where-Object { $_.state -eq 'PUBLISHED' -and $_.publish_attempts -eq 2 }).Count -eq 1 } 'Expired Relay claim republished'
    Wait-Until { (Broker GET "queues/$vhost/taskflow.worker.default.v1").messages_ready -eq 2 } 'Two copies in real Broker'
    $after = Snapshot 'relay-republished' $relayRun.id
    Check ($before.outbox[0].message_json -ceq $after.outbox[0].message_json -and $after.outbox[0].claim_version -eq 2 -and $before.attempts[0].lease_version -eq $after.attempts[0].lease_version) 'Relay recovery reuses event, immutable payload and execution lease; claim version increases'
    Stop-ServiceProcess worker; Start-ServiceProcess worker
    $result = Wait-Terminal $relayRun.id; Wait-Reported worker $result.attempts[0].id
    $final = Snapshot 'relay-final' $relayRun.id
    Check ($result.state -eq 'SUCCEEDED' -and $final.attempts.Count -eq 1 -and $final.inbox.worker.Count -eq 1 -and $final.inbox.worker[0].reported -and $final.outbox[0].state -eq 'PUBLISHED') 'Duplicate Broker deliveries converge to one successful Attempt and one reported Inbox'

    # Kill a Worker with an active execution lease, complete a new Attempt elsewhere, then restart its old Inbox.
    $crashRun = New-Run demoSlow 2 8000
    Wait-Until { (Get-Run $crashRun.id).attempts[0].ownerWorkerId -eq 'worker-default' } 'First Worker executing'
    Write-JsonFile 'worker-before-kill.json' (Get-Run $crashRun.id)
    Stop-ServiceProcess worker
    $killed = Snapshot 'worker-after-kill' $crashRun.id
    Check ($killed.inbox.worker[0].state -eq 'RUNNING' -and !$killed.inbox.worker[0].reported) 'Worker is forcibly killed while its durable Inbox is RUNNING'
    Start-ServiceProcess worker2; Wait-Node worker-second
    $result = Wait-Terminal $crashRun.id
    Check ($result.state -eq 'SUCCEEDED' -and $result.attempts.Count -eq 2 -and $result.attempts[0].state -eq 'TIMED_OUT' -and $result.attempts[1].targetWorkerId -eq 'worker-second') 'Killed running Worker is replaced by one bounded Attempt on the surviving Worker'
    $rejections = Count-RejectedResults
    Wait-Reported worker2 $result.attempts[1].id
    $replacement = Snapshot 'worker-replacement-complete' $crashRun.id
    Start-ServiceProcess worker; Wait-Node worker-default
    Wait-Reported worker $result.attempts[0].id
    $final = Snapshot 'worker-restarted-final' $crashRun.id
    Check ($final.inbox.worker[0].result_code -eq 'WORKER_RESTARTED' -and !$final.inbox.worker[0].retryable -and $final.inbox.worker[0].reported -and $final.inbox.worker[0].report_count -eq 1 -and
        $final.inbox.worker2.Count -eq 1 -and $final.inbox.worker2[0].state -eq 'SUCCEEDED' -and $final.inbox.worker2[0].reported -and
        @($final.outbox | Where-Object state -ne 'PUBLISHED').Count -eq 0 -and $final.outbox.Count -eq 2 -and (Count-RejectedResults) -gt $rejections -and
        ($replacement.run | ConvertTo-Json -Depth 30 -Compress) -ceq ($final.run | ConvertTo-Json -Depth 30 -Compress) -and
        ($replacement.attempts | ConvertTo-Json -Depth 30 -Compress) -ceq ($final.attempts | ConvertTo-Json -Depth 30 -Compress)) 'Restarted old Worker really reports its unknown result, receives HTTP 409 and preserves replacement success'

    # Persist a real old success but stop its report, then recover that Inbox after a replacement has succeeded.
    Stop-ServiceProcess worker2; Wait-Node worker-second OFFLINE
    [IO.File]::WriteAllText((Join-Path $root 'worker_execution-report.arm'), '*')
    $lateRun = New-Run demoSuccess 2
    Wait-Until { Test-Path -LiteralPath (Join-Path $root 'worker_execution-report.json') } 'Old success persisted before reporting'
    Stop-ServiceProcess worker
    $saved = Snapshot 'late-success-before-replacement' $lateRun.id
    Check ($saved.inbox.worker[0].state -eq 'SUCCEEDED' -and !$saved.inbox.worker[0].reported -and $saved.inbox.worker[0].report_count -eq 0) 'Old success survives a real process kill before its first result report'
    Start-ServiceProcess worker2; Wait-Node worker-second
    $result = Wait-Terminal $lateRun.id
    Wait-Reported worker2 $result.attempts[1].id
    $replacement = Snapshot 'late-success-replacement' $lateRun.id
    $rejections = Count-RejectedResults
    Start-ServiceProcess worker; Wait-Node worker-default
    Wait-Reported worker $result.attempts[0].id
    $final = Snapshot 'late-success-final' $lateRun.id
    Check ($result.state -eq 'SUCCEEDED' -and $result.attempts.Count -eq 2 -and $result.attempts[0].state -eq 'TIMED_OUT' -and
        $final.outbox.Count -eq 2 -and @($final.outbox | Where-Object state -ne 'PUBLISHED').Count -eq 0 -and
        $final.inbox.worker.Count -eq 1 -and $final.inbox.worker[0].state -eq 'SUCCEEDED' -and $final.inbox.worker[0].report_count -eq 1 -and $final.inbox.worker[0].reported -and
        $final.inbox.worker2.Count -eq 1 -and $final.inbox.worker2[0].state -eq 'SUCCEEDED' -and $final.inbox.worker2[0].reported -and (Count-RejectedResults) -gt $rejections -and
        ($replacement.run | ConvertTo-Json -Depth 30 -Compress) -ceq ($final.run | ConvertTo-Json -Depth 30 -Compress) -and
        ($replacement.attempts | ConvertTo-Json -Depth 30 -Compress) -ceq ($final.attempts | ConvertTo-Json -Depth 30 -Compress)) 'Real delayed success from recovered old Inbox gets 409; Run and both Attempts remain byte-for-byte unchanged'

    $failure = New-Run demoFail 2
    $result = Wait-Terminal $failure.id
    $final = Snapshot 'business-retry-limit' $failure.id
    Check ($result.state -eq 'FAILED' -and $final.attempts.Count -eq 2 -and @($final.attempts | Where-Object state -ne 'FAILED').Count -eq 0 -and $final.outbox.Count -eq 2) 'Handler failures stop at snapshot maxAttempts=2 within the original Run'

    # Consecutive real process deaths must also obey the same business retry budget.
    Stop-ServiceProcess worker2; Wait-Node worker-second OFFLINE
    $boundedRun = New-Run demoSlow 2 8000
    Wait-Until { (Get-Run $boundedRun.id).attempts[0].ownerWorkerId -eq 'worker-default' } 'First bounded crash owner'
    Stop-ServiceProcess worker
    Start-ServiceProcess worker2; Wait-Node worker-second
    Wait-Until {
        $current = Get-Run $boundedRun.id
        $current.attempts.Count -eq 2 -and $current.attempts[1].ownerWorkerId -eq 'worker-second'
    } 'Second bounded crash owner'
    Stop-ServiceProcess worker2
    $result = Wait-Terminal $boundedRun.id
    $exhaustedCrash = Snapshot 'consecutive-crashes-exhausted' $boundedRun.id
    Check ($result.state -eq 'FAILED' -and $result.attempts.Count -eq 2 -and @($result.attempts | Where-Object state -ne 'TIMED_OUT').Count -eq 0 -and
        $exhaustedCrash.inbox.worker[0].state -eq 'RUNNING' -and $exhaustedCrash.inbox.worker2[0].state -eq 'RUNNING') 'Two consecutive Worker deaths exhaust maxAttempts=2; both original Inboxes were killed while RUNNING'
    Start-ServiceProcess worker; Start-ServiceProcess worker2
    Wait-Node worker-default; Wait-Node worker-second
    Wait-Reported worker $result.attempts[0].id; Wait-Reported worker2 $result.attempts[1].id
    $final = Snapshot 'consecutive-crashes-recovered' $boundedRun.id
    Check ($final.attempts.Count -eq 2 -and $final.inbox.worker[0].result_code -eq 'WORKER_RESTARTED' -and $final.inbox.worker2[0].result_code -eq 'WORKER_RESTARTED' -and
        $final.outbox.Count -eq 2 -and @($final.outbox | Where-Object state -ne 'PUBLISHED').Count -eq 0 -and
        ($exhaustedCrash.run | ConvertTo-Json -Depth 30 -Compress) -ceq ($final.run | ConvertTo-Json -Depth 30 -Compress) -and
        ($exhaustedCrash.attempts | ConvertTo-Json -Depth 30 -Compress) -ceq ($final.attempts | ConvertTo-Json -Depth 30 -Compress)) 'Recovering both old Workers does not create a third Attempt or alter the exhausted Run'

    # Restore a short outage within the publication budget: the same event must finish.
    [IO.File]::WriteAllText((Join-Path $root 'amqp.block'), 'short publisher outage')
    Wait-Until { Test-Path -LiteralPath (Join-Path $root 'amqp.blocked') } 'Short publisher outage'
    $transientRun = New-Run
    $transientAttempt = $transientRun.attempts[0].id
    Wait-Until {
        $publication = @(Read-Db scheduler "SELECT state,publish_attempts FROM dispatch_outbox WHERE attempt_id='$transientAttempt'")[0]
        $publication.state -eq 'READY' -and $publication.publish_attempts -ge 1 -and $publication.publish_attempts -lt 3
    } 'Real failed publication persisted before recovery'
    Remove-Item -LiteralPath (Join-Path $root 'amqp.block')
    Remove-Item -LiteralPath (Join-Path $root 'amqp.blocked')
    $result = Wait-Terminal $transientRun.id
    $final = Snapshot 'publisher-transient-recovered' $transientRun.id
    Check ($result.state -eq 'SUCCEEDED' -and $final.attempts.Count -eq 1 -and $final.outbox.Count -eq 1 -and $final.outbox[0].state -eq 'PUBLISHED' -and
        $final.outbox[0].publish_attempts -gt 1 -and $final.outbox[0].publish_attempts -le 3 -and $final.outbox[0].attempt_id -eq $transientAttempt -and
        ($final.inbox.worker.Count + $final.inbox.worker2.Count) -eq 1) 'An in-flight event recovers after a real AMQP link cut within the same Run, Attempt and publication budget'

    # A publisher-only network cut keeps Worker heartbeats and Broker data untouched.
    [IO.File]::WriteAllText((Join-Path $root 'amqp.block'), 'block publisher')
    Wait-Until { Test-Path -LiteralPath (Join-Path $root 'amqp.blocked') } 'Publisher connections severed'
    $brokerRun = New-Run
    $result = Wait-Terminal $brokerRun.id
    $exhausted = Snapshot 'publisher-outage-exhausted' $brokerRun.id
    Check ($result.state -eq 'FAILED' -and $exhausted.attempts.Count -eq 1 -and $exhausted.outbox[0].state -eq 'FAILED' -and $exhausted.outbox[0].publish_attempts -eq 3 -and $exhausted.inbox.worker.Count -eq 0 -and $exhausted.inbox.worker2.Count -eq 0) 'Real AMQP connection failure exhausts exactly three publications without a new business Attempt or Inbox'
    Remove-Item -LiteralPath (Join-Path $root 'amqp.block')
    $recoverRun = New-Run
    $result = Wait-Terminal $recoverRun.id
    $recovered = Snapshot 'publisher-recovered' $recoverRun.id
    $unchanged = Snapshot 'publisher-exhausted-after-recovery' $brokerRun.id
    Check ($result.state -eq 'SUCCEEDED' -and $recovered.outbox[0].state -eq 'PUBLISHED' -and $unchanged.outbox[0].publish_attempts -eq 3 -and $unchanged.outbox[0].state -eq 'FAILED' -and $unchanged.attempts.Count -eq 1) 'Same Relay reconnects after AMQP recovery; exhausted event stays final and is never automatically replayed'
    $queues = @(Broker GET "queues/$vhost")
    Write-JsonFile 'quorum-final.json' @($queues | Select-Object name,type,members,online,messages_ready,messages_unacknowledged)
    Check (@($queues | Where-Object { $_.name -like '*.dead.v1' -and ($_.messages_ready -ne 0 -or $_.messages_unacknowledged -ne 0) }).Count -eq 0) 'Process and connection faults leave no unexpected dead letters'
    $completed = $true
} finally {
    foreach ($name in @('worker2','worker','scheduler')) { Stop-ServiceProcess $name }
    if ($proxy) { if (!$proxy.HasExited) { $proxy.Kill(); $null = $proxy.WaitForExit(10000) }; $proxy.Dispose() }
    try {
        Write-JsonFile 'report.json' @{date=(Get-Date -Format 'yyyy-MM-ddTHH:mm:sszzz');result=$(if ($completed) {'PASS'} else {'FAIL'});
            brokerVersion=$capability.version;legacyBroker=$capability.legacy;vhost=$vhost;checks=$checks;
            limitations=@('H2 file databases, not MySQL network faults','Publisher AMQP link cut, not Broker node shutdown or quorum loss','Reliable dead-letter transfer is a separate unverified acceptance')}
        if ($created -and $completed -and $vhost -match '^taskflow_it_[a-f0-9]{32}$') { $null = Broker DELETE "vhosts/$vhost" }
    } finally { $http.Dispose(); $management.Dispose() }
    Write-Output "Dispatch fault evidence: $root"
}

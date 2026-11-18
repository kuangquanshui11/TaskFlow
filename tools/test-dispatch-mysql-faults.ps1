param([Parameter(Mandatory)][string]$ClusterSettingsPath, [switch]$AllowLegacyBroker,
    [switch]$SkipBuild, [switch]$ConfirmNodeFailure, [switch]$OrderPaymentRace,
    [ValidateSet('Baseline','WorkerLink','PublisherShort','PublisherExhausted','QuorumShort','QuorumExhausted','BrokerPartition')]
    [string]$Scenario = 'Baseline', [switch]$DedicatedCluster)
$ErrorActionPreference = 'Stop'
if ($OrderPaymentRace -and ($ConfirmNodeFailure -or $Scenario -ne 'Baseline')) {
    throw '-OrderPaymentRace is a standalone publisher connection fault scenario.'
}
if ($ConfirmNodeFailure -and $Scenario -ne 'Baseline') { throw '-ConfirmNodeFailure applies to Baseline only.' }
if ($Scenario -in @('QuorumShort','QuorumExhausted','BrokerPartition') -and !$DedicatedCluster) {
    throw 'Topology faults require an isolated disposable cluster. Confirm its scope with -DedicatedCluster.'
}
$repoPath = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoPath
. (Join-Path $PSScriptRoot 'lib/mq-management.ps1')
. (Join-Path $PSScriptRoot 'lib/order-mq-race.ps1')
. (Join-Path $PSScriptRoot 'lib/dispatch-topology-faults.ps1')
$cluster = Read-MqClusterSettings $ClusterSettingsPath
$java = (Get-Command java -CommandType Application | Select-Object -First 1).Source
$node = (Get-Command node -CommandType Application | Select-Object -First 1).Source
$mysqld = (Get-Command mysqld -CommandType Application).Source
$mysqlBin = Split-Path -Parent $mysqld
if (!$SkipBuild) {
    & (Join-Path $repoPath 'mvnw.cmd') -B -pl taskflow-scheduler,taskflow-worker,examples/taskflow-order-service verify
    if ($LASTEXITCODE -ne 0) { throw 'Service verification failed.' }
}
$vhost = 'taskflow_it_' + [Guid]::NewGuid().ToString('N')
$root = Join-Path $repoPath ('.tools/dispatch-mysql/' + $vhost)
$classes = Join-Path $root 'classes'; $libs = Join-Path $root 'libs'
New-Item -ItemType Directory -Path $classes,$libs,(Join-Path $root 'data') -Force | Out-Null
$ports = @{}; $allocated = [Collections.Generic.HashSet[int]]::new()
foreach ($name in @('scheduler','worker','order','proxy','worker-link','mysql')) {
    do { $socket = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
        $socket.Start(); $port = $socket.LocalEndpoint.Port; $socket.Stop()
    } while (!$allocated.Add($port))
    $ports[$name] = $port
}
$processes = @{}; $checks = [Collections.Generic.List[object]]::new(); $saved = @{}
$management = New-MqManagementClient $cluster.username $cluster.password
$http = [Net.Http.HttpClient]::new(); $http.Timeout = [TimeSpan]::FromSeconds(20)
if ($OrderPaymentRace) { $http.Timeout = [TimeSpan]::FromSeconds(55) }
$base = $cluster.managementUrls[0]; $created = $false; $completed = $false; $faultNode = $null
$topologyRecoveryPending = $false
$token = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
$utf8 = [Text.UTF8Encoding]::new($false)
function Env([string]$Name, [string]$Value) {
    if (!$saved.ContainsKey($Name)) { $saved[$Name] = [Environment]::GetEnvironmentVariable($Name,'Process') }
    [Environment]::SetEnvironmentVariable($Name,$Value,'Process')
}
function Json([string]$Name, $Value) {
    [IO.File]::WriteAllText((Join-Path $root "$Name.json"),($Value | ConvertTo-Json -Depth 70),$utf8)
}
function Check([bool]$Value, [string]$Name) {
    $checks.Add(@{name=$Name;result=$(if ($Value) {'PASS'} else {'FAIL'});time=[DateTimeOffset]::Now.ToString('o')})
    if (!$Value) { throw "Acceptance failed: $Name. Evidence: $root" }
    Write-Host "PASS $Name"
}
function Wait-For([scriptblock]$Condition, [string]$Description, [int]$Seconds=90) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do { if (& $Condition) { return }; Start-Sleep -Milliseconds 200 } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $Description. Evidence: $root"
}
function Broker([string]$Method, [string]$Path, $Body=$null) { Invoke-MqManagement $management $base $Method $Path $Body }
function Request([string]$Service,[string]$Method,[string]$Path,$Body=$null) {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method),"http://127.0.0.1:$($ports[$Service])/$Path")
    $null = $request.Headers.TryAddWithoutValidation('X-Taskflow-Token',$token)
    if ($null -ne $Body) { $request.Content = [Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 30),[Text.Encoding]::UTF8,'application/json') }
    return $request
}
function Api([string]$Service,[string]$Method,[string]$Path,$Body=$null) {
    $request = Request $Service $Method $Path $Body
    try {
        $response = $http.SendAsync($request).GetAwaiter().GetResult()
        try {
            if (!$response.IsSuccessStatusCode) { throw "API $Service $Path HTTP $([int]$response.StatusCode)" }
            return ($response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json)
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}
function Db([string]$Name,[string]$Sql) {
    Env TASKFLOW_FAULT_DB_SCHEMA ($vhost + '_' + $Name)
    $output = & $java -cp "$classes;$libs/*" MysqlDispatchFaultProbe ($vhost + '_' + $Name) $Sql
    if ($LASTEXITCODE -ne 0) { throw "Evidence query failed: $Name" }
    return ($output | ConvertFrom-Json)
}
function Stop-Owned([string]$Name) {
    if (!$processes.ContainsKey($Name)) { return }
    $process = $processes[$Name]
    if (!$process.HasExited) { $process.Kill(); if (!$process.WaitForExit(10000)) { throw "Process did not exit: $Name" } }
    $process.Dispose(); $processes.Remove($Name)
}
function Start-App([string]$Name,[bool]$Consumer=$true) {
    Env TASKFLOW_FAULT_DB_SCHEMA ($vhost + '_' + $Name)
    Env TASKFLOW_FAULT_PORT ([string]$ports[$Name])
    $role = if ($Name -eq 'order') { 'worker' } else { $Name }
    $credentials = $cluster.serviceCredentials.$role
    if (!$credentials.username -or !$credentials.password) { throw "Independent $role credentials required" }
    Env TASKFLOW_FAULT_MQ_USERNAME $credentials.username
    Env TASKFLOW_FAULT_MQ_PASSWORD $credentials.password
    Env TASKFLOW_FAULT_MQ_ADDRESSES $(if ($Name -eq 'scheduler') { "127.0.0.1:$($ports.proxy)" } else { $cluster.addresses })
    $schedulerPort = if ($Name -eq 'worker' -and $Scenario -eq 'WorkerLink') { $ports['worker-link'] } else { $ports.scheduler }
    Env TASKFLOW_FAULT_SCHEDULER_URL "http://127.0.0.1:$schedulerPort"
    $stamp = Get-Date -Format HHmmssfff
    $config = (Join-Path $PSScriptRoot 'acceptance/application-dispatch-mysql.yml').Replace('\','/')
    if ($OrderPaymentRace -and $Name -eq 'order') {
        $config += ',file:' + (Join-Path $PSScriptRoot 'acceptance/application-order-mq-race.yml').Replace('\','/')
    }
    $arguments = @('-Xms64m','-Xmx256m',('"-Dloader.path=' + $classes + '"'),
        ('"-Dspring.config.additional-location=file:' + $config + '"'),
        ('-Dtaskflow.dispatch.consumer-enabled=' + $Consumer.ToString().ToLowerInvariant()))
    if ($OrderPaymentRace -and $Name -eq 'order') { $arguments += '-Dloader.main=OrderMqRaceProbe' }
    if ($OrderPaymentRace -and $Name -eq 'worker') { $arguments += '-Dtaskflow.order.request-timeout=45s' }
    if ($Name -eq 'scheduler') { $arguments += @('-Dloader.main=MysqlDispatchFaultProbe','-Dtaskflow.fault.boot=true',('"-Dtaskflow.fault.root=' + $root + '"')) }
    $arguments += @('-cp',('"' + (Join-Path $root "$Name.jar") + '"'),'org.springframework.boot.loader.launch.PropertiesLauncher')
    $processes[$Name] = Start-Process -FilePath $java -ArgumentList $arguments -WindowStyle Hidden -PassThru -WorkingDirectory $root `
        -RedirectStandardOutput (Join-Path $root "$Name-$stamp.out.log") -RedirectStandardError (Join-Path $root "$Name-$stamp.err.log")
    Wait-For {
        if ($processes[$Name].HasExited) { throw "$Name exited. Evidence: $root" }
        try { $response = $http.GetAsync("http://127.0.0.1:$($ports[$Name])/actuator/health").GetAwaiter().GetResult()
            try { return [int]$response.StatusCode -eq 200 } finally { $response.Dispose() }
        } catch { return $false }
    } "$Name healthy"
    if ($Name -eq 'worker') {
        Wait-For { try { (Api scheduler GET 'internal/v1/workers/selectById?workerId=worker-default').status -eq 'ONLINE' } catch { $false } } 'Worker registered'
    }
    Write-Output "$Name ready (PID $($processes[$Name].Id))"
}
function Arm([string]$Name) {
    foreach ($suffix in @('hit','json','release')) {
        $file = Join-Path $root "$Name.$suffix"
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file }
    }
    [IO.File]::WriteAllText((Join-Path $root "$Name.arm"),'armed')
}
function Wait-Gate([string]$Name) { Wait-For { Test-Path -LiteralPath (Join-Path $root "$Name.json") } "$Name barrier" }
function Run-Body([string]$Key) {
    return @{requestKey=$Key;snapshot=@{jobId=991;configVersion=1;name='mysql-dispatch-fault';handlerKey='orderTimeoutCheck';
        payload=@{batchSize=10;maxBatches=2};timeoutSeconds=900;maxAttempts=1;retryIntervalSeconds=1;
        scheduleType='MANUAL';timeZone='UTC';misfirePolicy='SKIP';enabled=$false}}
}
function Seed([int]$Sku) {
    $null = Api order POST 'api/v1/inventory/insert' @{skuId=$Sku;available=1}
    $order = Api order POST 'api/v1/orders/insert' @{requestKey=[Guid]::NewGuid().ToString('N');skuId=$Sku;quantity=1}
    Wait-For { @((Db order "SELECT id FROM purchase_order WHERE id='$($order.id)' AND expires_at<CURRENT_TIMESTAMP(6)")).Count -eq 1 } 'Order payment deadline'
    return $order.id
}
function Snapshot([string]$Label,[string]$Id,[int]$Sku) {
    $value = @{time=[DateTimeOffset]::Now.ToString('o');run=@(Db scheduler "SELECT * FROM run_execution WHERE id='$Id'");
        attempts=@(Db scheduler "SELECT * FROM run_attempt WHERE run_id='$Id' ORDER BY attempt_number");
        outbox=@(Db scheduler "SELECT * FROM dispatch_outbox WHERE attempt_id IN (SELECT id FROM run_attempt WHERE run_id='$Id')");
        inbox=@(Db worker "SELECT * FROM worker_execution WHERE run_id='$Id'");
        orders=@(Db order "SELECT * FROM purchase_order WHERE sku_id=$Sku");stock=@(Db order "SELECT * FROM sku_stock WHERE sku_id=$Sku");
        ledger=@(Db order "SELECT * FROM stock_ledger WHERE sku_id=$Sku ORDER BY operation")}
    Json $Label $value
    return $value
}
function Finish([string]$Label,[string]$Id,[int]$Sku) {
    Wait-For { (Api scheduler GET "internal/v1/runs/selectById?id=$Id").state -in @('SUCCEEDED','FAILED') } 'Run terminal'
    Wait-For { @((Db worker "SELECT reported FROM worker_execution WHERE run_id='$Id'") | Where-Object reported).Count -eq 1 } 'Inbox reported'
    $value = Snapshot $Label $Id $Sku
    Check ($value.run.Count -eq 1 -and $value.run[0].state -eq 'SUCCEEDED' -and $value.attempts.Count -eq 1 -and
        $value.attempts[0].state -eq 'SUCCEEDED' -and $value.outbox.Count -eq 1 -and $value.outbox[0].state -eq 'PUBLISHED' -and
        $value.inbox.Count -eq 1 -and $value.inbox[0].state -eq 'SUCCEEDED' -and $value.inbox[0].reported) "$Label`: one successful Run/Attempt/Outbox/Inbox"
    Check ($value.orders.Count -eq 1 -and $value.orders[0].state -eq 'CLOSED' -and $value.stock[0].available -eq 1 -and
        $value.stock[0].reserved -eq 0 -and @($value.ledger | Where-Object operation -eq 'RELEASED').Count -eq 1) "$Label`: order closed and inventory released exactly once"
    return $value
}
function Queue-Snapshot([string]$Label,[int]$Ready) {
    Wait-For { (Broker GET "queues/$vhost/taskflow.worker.default.v1").messages_ready -eq $Ready } "Broker ready=$Ready"
    $queues = @(Broker GET "queues/$vhost")
    Json $Label @($queues | Select-Object name,type,leader,members,online,messages_ready,messages_unacknowledged,message_stats)
}
try {
    Wait-For { try { $nodes=@(Broker GET nodes); return $nodes.Count -eq 3 -and @($nodes | Where-Object running).Count -eq 3 }
        catch { return $false } } 'Restore all three Broker nodes before testing' 300
    $capability = Get-MqBrokerCapability $management $base ([bool]$AllowLegacyBroker)
    $nodes = @(Broker GET nodes)
    Check ($nodes.Count -eq 3 -and @($nodes | Where-Object running).Count -eq 3) 'All three existing Broker nodes are online before testing'
    $disruptBroker = $Scenario -in @('QuorumShort','QuorumExhausted','BrokerPartition')
    if ($disruptBroker) {
        Check (@(Broker GET connections).Count -eq 0) 'Dedicated fault cluster has no pre-existing AMQP connections'
    }
    $null = Broker PUT "vhosts/$vhost" @{}; $created = $true
    $null = Broker PUT "permissions/$vhost/$([Uri]::EscapeDataString($cluster.username))" @{configure='.*';write='.*';read='.*'}
    foreach ($role in @('scheduler','worker')) {
        $credentials = $cluster.serviceCredentials.$role
        $permission = if ($role -eq 'scheduler') { @{configure='^$';write='^taskflow\.dispatch\.v1$';read='^$'} }
            else { @{configure='^$';write='^$';read='^taskflow\.worker\.default\.v1$'} }
        $null = Broker PUT "permissions/$vhost/$([Uri]::EscapeDataString($credentials.username))" $permission
    }
    foreach ($exchange in @('taskflow.dispatch.v1','taskflow.dispatch.dead.v1')) {
        $null = Broker PUT "exchanges/$vhost/$exchange" @{type='direct';durable=$true;auto_delete=$false;internal=$false;arguments=@{}}
    }
    foreach ($queue in @('taskflow.worker.default.v1','taskflow.worker.default.dead.v1')) {
        $null = Broker PUT "queues/$vhost/$queue" @{durable=$true;auto_delete=$false;arguments=@{'x-queue-type'='quorum';'x-quorum-initial-group-size'=3}}
    }
    $null = Broker POST "bindings/$vhost/e/taskflow.dispatch.v1/q/taskflow.worker.default.v1" @{routing_key='worker.default';arguments=@{}}
    $null = Broker PUT "policies/$vhost/safety" (New-MqDispatchSafetyPolicy $capability.legacy)
    $topology = @(Broker GET "queues/$vhost")
    Json quorum-before @($topology | Select-Object name,type,leader,members,online)
    Check (@($topology | Where-Object { $_.type -ne 'quorum' -or @($_.online).Count -ne 3 }).Count -eq 0) 'Isolated quorum queues have three online replicas'
    foreach ($name in @('scheduler','worker','order')) {
        $source = if ($name -eq 'order') { 'examples/taskflow-order-service/target/taskflow-order-service-0.0.1-SNAPSHOT.jar' }
            else { "taskflow-$name/target/taskflow-$name-0.0.1-SNAPSHOT.jar" }
        Copy-Item -LiteralPath (Join-Path $repoPath $source) -Destination (Join-Path $root "$name.jar")
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $root 'scheduler.jar'))
    try { foreach ($entry in $archive.Entries | Where-Object FullName -Like 'BOOT-INF/lib/*.jar') {
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,(Join-Path $libs $entry.Name))
    } } finally { $archive.Dispose() }
    & (Join-Path (Split-Path -Parent $java) 'javac.exe') -cp "$libs/*" -d $classes (Join-Path $PSScriptRoot 'local/MysqlDispatchFaultProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Fault probe compilation failed.' }
    $dataPath = Join-Path $root 'data'
    & $mysqld --no-defaults --initialize-insecure "--basedir=$(Split-Path -Parent $mysqlBin)" "--datadir=$dataPath" "--log-error=$(Join-Path $root 'mysql-initialize.log')"
    if ($LASTEXITCODE -ne 0) { throw 'Temporary MySQL initialization failed.' }
    $processes.mysql = Start-Process -FilePath $mysqld -ArgumentList @('--no-defaults',('--basedir="' + (Split-Path -Parent $mysqlBin) + '"'),
        ('--datadir="' + $dataPath + '"'),'--bind-address=127.0.0.1',('--port=' + $ports.mysql),'--mysqlx=0','--skip-log-bin',
        ('--log-error="' + (Join-Path $root 'mysql-server.log') + '"')) -WindowStyle Hidden -PassThru
    Wait-For {
        if ($processes.mysql.HasExited) { throw 'Temporary MySQL exited.' }
        & (Join-Path $mysqlBin 'mysqladmin.exe') --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$($ports.mysql)" --user=root ping *> $null
        return $LASTEXITCODE -eq 0
    } 'Temporary MySQL ready'
    foreach ($name in @('scheduler','worker','order')) {
        & (Join-Path $mysqlBin 'mysql.exe') --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$($ports.mysql)" --user=root "--execute=CREATE DATABASE $($vhost)_$name CHARACTER SET utf8mb4"
        if ($LASTEXITCODE -ne 0) { throw 'Test schema creation failed.' }
    }
    $targets = @($cluster.addresses.Split(',') | ForEach-Object { $uri=[Uri]('amqp://' + $_); @{host=$uri.Host;port=$uri.Port} })
    $proxyTargets = if ($Scenario -eq 'BrokerPartition') { @($targets[0]) } else { $targets }
    Json proxy @{port=$ports.proxy;targets=@($proxyTargets)}
    $processes.proxy = Start-Process -FilePath $node -ArgumentList @(('"' + (Join-Path $PSScriptRoot 'local/mq-confirm-fault-proxy.mjs') + '"'),('"' + (Join-Path $root 'proxy.json') + '"')) `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'proxy.out.log') -RedirectStandardError (Join-Path $root 'proxy.err.log')
    Wait-For { Test-Path -LiteralPath (Join-Path $root 'proxy.ready') } 'Proxy ready'
    Env TASKFLOW_FAULT_VHOST $vhost
    Env TASKFLOW_FAULT_DB_PORT ([string]$ports.mysql)
    Env TASKFLOW_FAULT_YAML (Join-Path $PSScriptRoot 'acceptance/application-dispatch-mysql.yml')
    Env TASKFLOW_FAULT_TOKEN $token
    Env TASKFLOW_FAULT_SCHEDULER_URL "http://127.0.0.1:$($ports.scheduler)"
    Env TASKFLOW_FAULT_ORDER_URL "http://127.0.0.1:$($ports.order)"
    Env TASKFLOW_FAULT_WORKER_URL "http://127.0.0.1:$($ports.worker)"
    Env TASKFLOW_FAULT_CONFIRM_TIMEOUT '1s'
    Env TASKFLOW_FAULT_CLAIM_LEASE $(if ($disruptBroker) { '300s' } elseif ($Scenario -like 'Publisher*') { '30s' } else { '8s' })
    Env TASKFLOW_FAULT_PUBLISH_RETRY_DELAY $(if ($Scenario -in @('QuorumShort','BrokerPartition')) { '45s' }
        elseif ($Scenario -eq 'PublisherShort') { '6s' } else { '2s' })
    Env TASKFLOW_FAULT_EXECUTION_LEASE $(if ($Scenario -eq 'WorkerLink') { '12s' } else { '300s' })
    Env TASKFLOW_FAULT_RENEWAL_INTERVAL $(if ($Scenario -eq 'WorkerLink') { '1s' } else { '5s' })
    if ($Scenario -eq 'WorkerLink') {
        Json worker-link @{port=$ports['worker-link'];schedulerPort=$ports.scheduler}
        $processes['worker-link'] = Start-Process -FilePath $node -ArgumentList @(
            ('"' + (Join-Path $PSScriptRoot 'local/worker-link-fault-proxy.mjs') + '"'),
            ('"' + (Join-Path $root 'worker-link.json') + '"')) -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $root 'worker-link.out.log') -RedirectStandardError (Join-Path $root 'worker-link.err.log')
        Wait-For { Test-Path -LiteralPath (Join-Path $root 'worker-link.ready') } 'Worker link proxy ready'
    }
    if ($OrderPaymentRace) {
        Env TASKFLOW_FAULT_CONFIRM_TIMEOUT '45s'
        Env TASKFLOW_FAULT_CLAIM_LEASE '55s'
        Env TASKFLOW_ORDER_RACE_ROOT $root
        Env TASKFLOW_ORDER_RACE_PAYMENT_KEY ([Guid]::NewGuid().ToString('N'))
        & (Join-Path (Split-Path -Parent $java) 'javac.exe') -cp "$libs/*" -d $classes (Join-Path $PSScriptRoot 'local/OrderMqRaceProbe.java')
        if ($LASTEXITCODE -ne 0) { throw 'Order race probe compilation failed.' }
        & $node (Join-Path $PSScriptRoot 'local/order-payment-notification.mjs') init
        if ($LASTEXITCODE -ne 0) { throw 'Order payment test key generation failed.' }
    }
    Start-App order; Start-App scheduler; Start-App worker
    Json environment @{time=[DateTimeOffset]::Now.ToString('o');ports=$ports;brokerVersion=$capability.version;
        database=@(Db scheduler 'SELECT VERSION() AS version, @@transaction_isolation AS isolation_level');
        jars=@(Get-FileHash (Join-Path $root '*.jar') -Algorithm SHA256 | Select-Object Hash,Path)}

    if ($OrderPaymentRace) {
        Invoke-OrderPaymentRaces
    } elseif ($Scenario -eq 'WorkerLink') {
        Invoke-WorkerLinkFault
    } elseif ($Scenario -ne 'Baseline') {
        Invoke-PublisherAvailabilityFault
    } else {
    # The commit is paused with real InnoDB changes still uncommitted, then the JVM is killed.
    $order = Seed 41001; $key = [Guid]::NewGuid().ToString(); $body = Run-Body $key
    Arm before-commit
    $request = Request scheduler POST 'internal/v1/runs/insert' $body
    $pending = $http.SendAsync($request)
    Wait-Gate before-commit
    $visible = @{runs=@(Db scheduler "SELECT * FROM run_execution WHERE request_key='$key'");
        attempts=@(Db scheduler 'SELECT * FROM run_attempt');outbox=@(Db scheduler 'SELECT * FROM dispatch_outbox');
        transactions=@(Db scheduler 'SELECT trx_id,trx_state,trx_rows_modified FROM information_schema.innodb_trx')}
    Json before-commit-visible $visible
    Check ($visible.runs.Count -eq 0 -and $visible.attempts.Count -eq 0 -and $visible.outbox.Count -eq 0 -and
        @($visible.transactions | Where-Object trx_rows_modified -ge 3).Count -ge 1) 'Commit barrier holds real uncommitted InnoDB writes invisible to an independent reader'
    Stop-Owned scheduler
    try { $response = $pending.GetAwaiter().GetResult(); $response.Dispose() } catch { } finally { $request.Dispose() }
    $rollback = @{runs=@(Db scheduler 'SELECT * FROM run_execution');attempts=@(Db scheduler 'SELECT * FROM run_attempt');
        outbox=@(Db scheduler 'SELECT * FROM dispatch_outbox');inbox=@(Db worker 'SELECT * FROM worker_execution')}
    Json before-commit-after-kill $rollback
    Check ($rollback.runs.Count -eq 0 -and $rollback.attempts.Count -eq 0 -and $rollback.outbox.Count -eq 0 -and $rollback.inbox.Count -eq 0) 'Kill before MySQL commit rolls back Run, Attempt and Outbox; Inbox stays empty'
    Queue-Snapshot before-commit-broker 0
    Start-App scheduler
    $run = Api scheduler POST 'internal/v1/runs/insert' $body
    $final = Finish before-commit-recovered $run.id 41001
    $same = Api scheduler POST 'internal/v1/runs/insert' $body
    Check ($same.id -eq $run.id) 'Retrying the interrupted request key creates one Run and returns that Run thereafter'

    # The publisher barrier is reached only after preparation AND Relay claim have committed.
    Stop-Owned worker; Start-App worker $false
    $order = Seed 41002
    Arm before-publish
    $run = Api scheduler POST 'internal/v1/runs/insert' (Run-Body ([Guid]::NewGuid().ToString()))
    Wait-Gate before-publish
    Stop-Owned scheduler
    $before = Snapshot after-commit-before-publish $run.id 41002
    Queue-Snapshot after-commit-broker 0
    Check ($before.run.Count -eq 1 -and $before.attempts.Count -eq 1 -and $before.outbox.Count -eq 1 -and
        $before.outbox[0].state -eq 'SENDING' -and $before.outbox[0].publish_attempts -eq 1 -and $before.inbox.Count -eq 0 -and
        $before.orders[0].state -eq 'PENDING' -and @($before.ledger | Where-Object operation -eq 'RELEASED').Count -eq 0) 'Kill after commit but before send preserves committed Outbox without message or business effect'
    Start-App scheduler
    Wait-For { @(Db scheduler "SELECT state FROM dispatch_outbox WHERE event_id='$($before.outbox[0].event_id)' AND state='PUBLISHED'").Count -eq 1 } 'Expired Relay claim recovered'
    Queue-Snapshot after-commit-republished-broker 1
    Stop-Owned worker; Start-App worker
    $final = Finish after-commit-recovered $run.id 41002
    Check ($final.outbox[0].event_id -eq $before.outbox[0].event_id -and $final.outbox[0].message_json -ceq $before.outbox[0].message_json -and
        $final.outbox[0].publish_attempts -eq 2 -and $final.outbox[0].claim_version -eq 2 -and
        $final.attempts[0].lease_version -eq $before.attempts[0].lease_version) 'Recovery reuses immutable event and execution lease with a new Relay claim'

    Stop-Owned worker; Start-App worker $false
    Stop-Owned scheduler
    Env TASKFLOW_FAULT_CONFIRM_TIMEOUT '240s'; Env TASKFLOW_FAULT_CLAIM_LEASE '300s'
    Start-App scheduler
    $order = Seed 41003
    Arm confirm
    $run = Api scheduler POST 'internal/v1/runs/insert' (Run-Body ([Guid]::NewGuid().ToString()))
    Wait-Gate confirm
    $before = Snapshot confirm-held $run.id 41003
    Queue-Snapshot confirm-held-broker 1
    Check ($before.outbox[0].state -eq 'SENDING' -and $before.outbox[0].publish_attempts -eq 1 -and $before.inbox.Count -eq 0) 'Real Basic.Ack is withheld while publisher is waiting and original message is durable in quorum'
    $pids = @{scheduler=$processes.scheduler.Id;worker=$processes.worker.Id}
    if ($ConfirmNodeFailure) {
        # Match the actual proxy upstream to a direct management endpoint; never guess the node name.
        $target = (Get-Content (Join-Path $root 'confirm.json') -Raw | ConvertFrom-Json).target
        $index = [array]::FindIndex([object[]]$targets,[Predicate[object]]{param($item) $item.host -eq $target.host -and $item.port -eq $target.port})
        $overview = Invoke-MqManagement $management $cluster.managementUrls[$index] GET overview
        $faultNode = $overview.node
        $base = $cluster.managementUrls[(($index + 1) % 3)]
        Write-Output "STOP NODE NOW: $faultNode. Keep the other two running. Waiting up to 180 seconds."
        Wait-For {
            try { $current = @(Broker GET nodes); $queues = @(Broker GET "queues/$vhost") }
            catch { return $false }
            if (@($current | Where-Object { $_.name -eq $faultNode -and !$_.running }).Count -eq 1 -and
                $queues.Count -eq 2 -and
                @($queues | Where-Object { @($_.online).Count -ne 2 }).Count -eq 0) {
                Json confirm-node-down @{time=[DateTimeOffset]::Now.ToString('o');nodes=@($current | Select-Object name,running);
                    queues=@($queues | Select-Object name,type,leader,members,online)}
                return $true
            }
            return $false
        } 'Specified Broker node exited during pending confirmation' 180
    } else {
        # Unattended mode accepts connection loss only, not a Broker process failure.
        [IO.File]::WriteAllText((Join-Path $root 'confirm.cut'),'drop pending confirmation')
    }
    Wait-For { @((Db scheduler "SELECT * FROM dispatch_outbox WHERE event_id='$($before.outbox[0].event_id)'") | Where-Object { $_.publish_attempts -eq 2 -and $_.state -eq 'PUBLISHED' }).Count -eq 1 } 'Unconfirmed event republished through surviving Broker'
    Queue-Snapshot confirm-republished-broker 2
    Check ($processes.scheduler.Id -eq $pids.scheduler -and $processes.worker.Id -eq $pids.worker) 'Original Scheduler and Worker processes survive confirmation loss'
    Stop-Owned worker; Start-App worker
    $final = Finish confirm-recovered $run.id 41003
    $timeline = Api scheduler GET "internal/v1/runs/selectAttemptEvents?runId=$($run.id)&attemptId=$($final.attempts[0].id)"
    Json confirm-timeline $timeline
    Check ($final.outbox[0].event_id -eq $before.outbox[0].event_id -and $final.outbox[0].message_json -ceq $before.outbox[0].message_json -and
        $final.outbox[0].publish_attempts -eq 2 -and $final.attempts[0].lease_version -eq $before.attempts[0].lease_version -and
        @($timeline.worker.events | Where-Object eventType -eq 'HANDLER_STARTED').Count -eq 1) 'Two durable Broker copies converge to one Handler entry with unchanged event and lease'
    if ($faultNode) {
        Write-Output "RESTORE NODE NOW: $faultNode. Waiting up to 180 seconds."
        Wait-For { try { $queues=@(Broker GET "queues/$vhost"); return $queues.Count -eq 2 -and @($queues | Where-Object { @($_.online).Count -ne 3 }).Count -eq 0 }
            catch { return $false } } 'All three quorum replicas restored' 180
        $faultNode = $null
        Check $true 'Stopped Broker restored with all three quorum replicas online'
    }
    }
    Queue-Snapshot quorum-final 0
    $queues = @(Broker GET "queues/$vhost")
    Check (@($queues | Where-Object { $_.messages_ready -ne 0 -or $_.messages_unacknowledged -ne 0 }).Count -eq 0) 'All test queues including DLQ drained after recovery'
    foreach ($name in @('scheduler','worker','order')) { Json "$name-migrations" @(Db $name 'SELECT version,description,success FROM flyway_schema_history ORDER BY installed_rank') }
    $completed = $true
} finally {
    if ($faultNode) { Write-Warning "RESTORE REQUIRED: $faultNode. Test did not accept node recovery." }
    if ($topologyRecoveryPending) { Write-Warning 'RESTORE REQUIRED: remove the injected Broker fault and verify all replicas are online.' }
    foreach ($name in @('worker','scheduler','order','worker-link','proxy')) { Stop-Owned $name }
    if ($processes.ContainsKey('mysql')) {
        & (Join-Path $mysqlBin 'mysqladmin.exe') --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$($ports.mysql)" --user=root shutdown *> $null
        if (!$processes.mysql.WaitForExit(10000)) { Stop-Owned mysql } else { $processes.mysql.Dispose(); $processes.Remove('mysql') }
    }
    if ($created -and $completed) { $null = Broker DELETE "vhosts/$vhost" }
    Json report @{time=[DateTimeOffset]::Now.ToString('o');completed=$completed;brokerVersion=$capability.version;scenario=$(if ($OrderPaymentRace) {'OrderPaymentRace'} else {$Scenario});
        confirmFault=$(if ($Scenario -eq 'Baseline') { if ($ConfirmNodeFailure) {'BROKER_NODE_EXIT'} else {'PUBLISHER_CONNECTION_LOSS'} });checks=$checks;
        boundaries=@('Single host temporary MySQL, independent schemas','Physical host failure not covered',
            'No arbitrary external side-effect exactly-once guarantee','Legacy Broker does not accept at-least-once dead-letter transfer')}
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name,$saved[$name],'Process') }
    $http.Dispose(); $management.Dispose()
    Write-Output "Evidence: $root; completed=$completed"
}

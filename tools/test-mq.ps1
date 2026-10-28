param([switch]$SkipBuild, [switch]$SkipBrowser, [switch]$SkipFailover,
    [string]$ClusterSettingsPath, [switch]$ManualFailover, [switch]$AllowLegacyBroker)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
$clusterPath = Join-Path $repoPath 'infra/rabbitmq'
$envPath = Join-Path $clusterPath '.env'
. (Join-Path $PSScriptRoot 'lib/mq-management.ps1')
if ($ClusterSettingsPath) {
    $clusterSettings = Read-MqClusterSettings $ClusterSettingsPath
    if (!$SkipFailover -and !$ManualFailover) { throw 'External cluster fault injection requires -ManualFailover (or explicitly -SkipFailover).' }
} else {
    if ($ManualFailover) { throw '-ManualFailover requires external ClusterSettingsPath.' }
    if (!(Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker Compose and a running three-node TaskFlow MQ cluster are required.' }
    if (!(Test-Path -LiteralPath $envPath)) { throw 'Initialize and start the cluster with tools/mq-cluster.ps1 first.' }
    $adminPassword = $null
    foreach ($line in [IO.File]::ReadAllLines($envPath)) {
        if ($line.StartsWith('TASKFLOW_MQ_ADMIN_PASSWORD=')) { $adminPassword = $line.Substring('TASKFLOW_MQ_ADMIN_PASSWORD='.Length) }
    }
    if (!$adminPassword) { throw 'MQ test admin credentials are missing.' }
    $clusterSettings = [pscustomobject]@{addresses='127.0.0.1:5672,127.0.0.1:5673,127.0.0.1:5674';
        username='taskflow_mq_admin';password=$adminPassword;
        managementUrls=@('http://127.0.0.1:15672','http://127.0.0.1:15673','http://127.0.0.1:15674')}
}
$testVhost = 'taskflow_it_' + [Guid]::NewGuid().ToString('N')
$runPath = Join-Path $repoPath ('.tools/mq-e2e/' + $testVhost)
New-Item -ItemType Directory -Path $runPath -Force | Out-Null
$settingsPath = Join-Path $runPath 'settings.json'
$utf8 = [Text.UTF8Encoding]::new($false)
$client = New-MqManagementClient $clusterSettings.username $clusterSettings.password
$managementUrl = $clusterSettings.managementUrls[0]
$phases = [Collections.Generic.List[object]]::new()
function Invoke-Broker([string]$Method, [string]$Path, $Body = $null) {
    Invoke-MqManagement $client $managementUrl $Method $Path $Body
}
function Invoke-Compose([Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArguments) {
    & docker compose --env-file $envPath -f (Join-Path $clusterPath 'compose.yml') @ComposeArguments
    if ($LASTEXITCODE -ne 0) { throw 'MQ fault-injection Compose command failed.' }
}
function Write-TestSettings {
    $settings = @{addresses=$clusterSettings.addresses;virtualHost=$testVhost;
        username=$clusterSettings.username;password=$clusterSettings.password;managementUrl=$managementUrl}
    if ($clusterSettings.serviceCredentials) { $settings.serviceCredentials = $clusterSettings.serviceCredentials }
    [IO.File]::WriteAllText($settingsPath,($settings | ConvertTo-Json -Depth 10),$utf8)
}
function Wait-Topology([int]$OnlineCount, [string]$Label) {
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        $nodes = @(Invoke-Broker 'GET' 'nodes')
        $queues = @(Invoke-Broker 'GET' ("queues/" + $testVhost))
        $valid = $nodes.Count -eq 3 -and @($nodes | Where-Object running).Count -eq $OnlineCount -and $queues.Count -eq 2
        $valid = $valid -and @($nodes | Where-Object { $_.running -and ($_.mem_alarm -or $_.disk_free_alarm -or @($_.partitions).Count -gt 0) }).Count -eq 0
        foreach ($queue in $queues) {
            $valid = $valid -and $queue.type -eq 'quorum' -and @($queue.members).Count -eq 3 -and @($queue.online).Count -eq $OnlineCount
        }
        if ($OnlineCount -eq 2) { $valid = $valid -and @($nodes | Where-Object { $_.name -eq $capability.node -and $_.running }).Count -eq 0 }
        if ($valid) {
            [pscustomobject]@{nodes=@($nodes | Select-Object name,running,partitions,mem_alarm,disk_free_alarm);
                queues=@($queues | Select-Object name,type,leader,members,online,messages_ready,messages_unacknowledged)} |
                ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $runPath ($Label + '.json'))
            Write-Output "PASS $Label`: three quorum members, $OnlineCount online replicas per queue."
            return
        }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Topology did not reach $OnlineCount online replicas on both three-member queues."
}
function Invoke-ExecutionAcceptance([string]$Label, [bool]$SkipPhaseBuild) {
    Write-TestSettings
    & (Join-Path $PSScriptRoot 'test-execution.ps1') -Transport rabbitmq -MqSettingsPath $settingsPath -SkipBuild:$SkipPhaseBuild -SkipBrowser:$SkipBrowser |
        Tee-Object -FilePath (Join-Path $runPath ($Label + '.log')) | Tee-Object -Variable phaseOutput
    $location = @($phaseOutput | Where-Object { "$_".StartsWith('Execution evidence: ') })
    if ($location.Count -ne 1) { throw 'Execution acceptance did not produce a completed evidence report.' }
    $evidencePath = "$($location[0])".Substring('Execution evidence: '.Length)
    $checks = @(Get-Content -LiteralPath (Join-Path $evidencePath 'report.json') -Raw | ConvertFrom-Json)
    if (!$checks.Count -or @($checks | Where-Object { $_.result -ne 'PASS' }).Count) { throw 'Execution report contains incomplete checks.' }
    $phases.Add([pscustomobject]@{phase=$Label;checks=$checks.Count;evidencePath=$evidencePath;result='PASS'})
}
function Invoke-BackpressureAcceptance {
    $variables = @{
        TASKFLOW_TEST_MQ_ADDRESSES = $clusterSettings.addresses
        TASKFLOW_TEST_MQ_VHOST = $testVhost
        TASKFLOW_TEST_MQ_USERNAME = $clusterSettings.username
        TASKFLOW_TEST_MQ_PASSWORD = $clusterSettings.password
    }
    $previous = @{}
    $testLog = Join-Path $runPath 'backpressure.log'
    try {
        foreach ($key in $variables.Keys) {
            $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
            [Environment]::SetEnvironmentVariable($key, $variables[$key], 'Process')
        }
        & (Join-Path $repoPath 'mvnw.cmd') -pl taskflow-worker '-Dtest=DispatchBackpressureBrokerTests' test *> $testLog
        if ($LASTEXITCODE -ne 0) { throw "MQ backpressure acceptance failed; inspect $testLog" }
        $reportPath = Join-Path $repoPath 'taskflow-worker/target/surefire-reports/TEST-com.taskflow.worker.dispatch.DispatchBackpressureBrokerTests.xml'
        [xml]$report = Get-Content -LiteralPath $reportPath -Raw
        if ([int]$report.testsuite.tests -ne 1 -or [int]$report.testsuite.skipped -ne 0 -or
            [int]$report.testsuite.failures -ne 0 -or [int]$report.testsuite.errors -ne 0) {
            throw 'MQ backpressure acceptance did not execute and pass its real Broker test.'
        }
        $phases.Add([pscustomobject]@{phase='capacity-backpressure';checks=1;evidencePath=$testLog;result='PASS'})
        Write-Output 'PASS real MQ: full Worker waits beyond failure budget, accepts original delivery after capacity returns, and sends no busy/expired delivery to dead letters.'
    } finally {
        foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    }
}
function Restore-FaultNode {
    if ($ManualFailover) { $null = Read-Host "Restore node $($capability.node), then press Enter" }
    else { Invoke-Compose @('start','rabbit1') }
}
$created = $false; $stopped = $false; $completed = $false
try {
    $capability = Get-MqBrokerCapability $client $managementUrl ([bool]$AllowLegacyBroker)
    $nodes = @(Invoke-Broker 'GET' 'nodes')
    if (@($nodes | Where-Object { $_.running }).Count -ne 3) { throw 'Acceptance requires all three cluster nodes online before injecting faults.' }
    $null = Invoke-Broker 'PUT' ("vhosts/" + $testVhost) @{}
    $created = $true
    $testUser = [Uri]::EscapeDataString($clusterSettings.username)
    $null = Invoke-Broker 'PUT' ("permissions/$testVhost/$testUser") @{configure='.*';write='.*';read='.*'}
    if ($clusterSettings.serviceCredentials) {
        foreach ($name in @('scheduler','worker')) {
            $role = $clusterSettings.serviceCredentials.$name
            if (!$role.username -or !$role.password -or $role.username -eq $clusterSettings.username) { throw 'Each service requires its own complete non-admin role credentials.' }
            $permissions = if ($name -eq 'scheduler') { @{configure='^$';write='^taskflow\.dispatch\.v1$';read='^$'} }
                else { @{configure='^$';write='^$';read='^taskflow\.worker\.default\.v1$'} }
            $roleName = [Uri]::EscapeDataString($role.username)
            $null = Invoke-Broker 'PUT' ("permissions/$testVhost/$roleName") $permissions
        }
    }
    foreach ($exchange in @('taskflow.dispatch.v1','taskflow.dispatch.dead.v1')) {
        $null = Invoke-Broker 'PUT' ("exchanges/$testVhost/$exchange") @{type='direct';durable=$true;auto_delete=$false;internal=$false;arguments=@{}}
    }
    foreach ($queue in @('taskflow.worker.default.v1','taskflow.worker.default.dead.v1')) {
        $null = Invoke-Broker 'PUT' ("queues/$testVhost/$queue") @{durable=$true;auto_delete=$false;arguments=@{'x-queue-type'='quorum';'x-quorum-initial-group-size'=3}}
    }
    $null = Invoke-Broker 'POST' ("bindings/$testVhost/e/taskflow.dispatch.v1/q/taskflow.worker.default.v1") @{routing_key='worker.default';arguments=@{}}
    $null = Invoke-Broker 'POST' ("bindings/$testVhost/e/taskflow.dispatch.dead.v1/q/taskflow.worker.default.dead.v1") @{routing_key='worker.default.dead';arguments=@{}}
    $null = Invoke-Broker 'PUT' ("policies/$testVhost/dispatch-safety") (New-MqDispatchSafetyPolicy $capability.legacy)
    Wait-Topology 3 'quorum-before'
    Invoke-BackpressureAcceptance
    Invoke-ExecutionAcceptance 'three-nodes' ([bool]$SkipBuild)
    if (!$SkipFailover) {
        $stopped = $true
        if ($ManualFailover) { $null = Read-Host "Stop node $($capability.node), then press Enter" }
        else { Invoke-Compose @('stop','rabbit1') }
        $managementUrl = $clusterSettings.managementUrls[1]
        Wait-Topology 2 'quorum-one-node-down'
        Invoke-ExecutionAcceptance 'one-node-down' $true
        Restore-FaultNode
        Wait-Topology 3 'quorum-restored'
        $stopped = $false
    }
    [pscustomobject]@{brokerVersion=$capability.version;legacyBroker=$capability.legacy;
        deadLetterStrategy=$(if ($capability.legacy) {'at-most-once'} else {'at-least-once'});
        faultNode=$capability.node;failoverTested=(!$SkipFailover);phases=$phases} |
        ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $runPath 'report.json')
    $completed = $true
    Write-Output "MQ acceptance passed. Evidence: $runPath"
} finally {
    try {
        if ($stopped) { Restore-FaultNode; Wait-Topology 3 'quorum-recovered-after-failure' }
        # 仅删除本轮随机创建的测试 vhost；失败时保留以便排查，不清空业务队列。
        if ($created -and $completed -and $testVhost -match '^taskflow_it_[a-f0-9]{32}$') { $null = Invoke-Broker 'DELETE' ("vhosts/" + $testVhost) }
    } finally { $client.Dispose() }
}

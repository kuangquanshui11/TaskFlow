param([switch]$SkipBuild, [switch]$SkipBrowser, [switch]$SkipFailover)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
$clusterPath = Join-Path $repoPath 'infra/rabbitmq'
$envPath = Join-Path $clusterPath '.env'
if (!(Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker Compose and a running three-node TaskFlow MQ cluster are required.' }
if (!(Test-Path -LiteralPath $envPath)) { throw 'Initialize and start the cluster with tools/mq-cluster.ps1 first.' }
$adminPassword = $null
foreach ($line in [IO.File]::ReadAllLines($envPath)) {
    if ($line.StartsWith('TASKFLOW_MQ_ADMIN_PASSWORD=')) { $adminPassword = $line.Substring('TASKFLOW_MQ_ADMIN_PASSWORD='.Length) }
}
if (!$adminPassword) { throw 'MQ test admin credentials are missing.' }
$testVhost = 'taskflow_it_' + [Guid]::NewGuid().ToString('N')
$runPath = Join-Path $repoPath ('.tools/mq-e2e/' + $testVhost)
New-Item -ItemType Directory -Path $runPath -Force | Out-Null
$settingsPath = Join-Path $runPath 'settings.json'
$utf8 = [Text.UTF8Encoding]::new($false)
$client = [Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(10)
$client.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Basic',
    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('taskflow_mq_admin:' + $adminPassword)))
function Invoke-Broker([string]$Method, [string]$Path, $Body = $null) {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), ('http://127.0.0.1:15672/api/' + $Path))
    if ($null -ne $Body) { $request.Content = [Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 20 -Compress),[Text.Encoding]::UTF8,'application/json') }
    try {
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        try {
            if (!$response.IsSuccessStatusCode) { throw "RabbitMQ test setup failed with HTTP $([int]$response.StatusCode)." }
            $bodyText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ($bodyText) { return $bodyText | ConvertFrom-Json }
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}
function Invoke-Compose([Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArguments) {
    & docker compose --env-file $envPath -f (Join-Path $clusterPath 'compose.yml') @ComposeArguments
    if ($LASTEXITCODE -ne 0) { throw 'MQ fault-injection Compose command failed.' }
}
function Write-TestSettings([int]$ManagementPort) {
    $settings = @{addresses='127.0.0.1:5672,127.0.0.1:5673,127.0.0.1:5674';virtualHost=$testVhost;
        username='taskflow_mq_admin';password=$adminPassword;managementUrl="http://127.0.0.1:$ManagementPort"}
    [IO.File]::WriteAllText($settingsPath,($settings | ConvertTo-Json),$utf8)
}
$created = $false; $stopped = $false; $completed = $false
try {
    $nodes = @(Invoke-Broker 'GET' 'nodes')
    if (@($nodes | Where-Object { $_.running }).Count -ne 3) { throw 'Acceptance requires all three cluster nodes online before injecting faults.' }
    $null = Invoke-Broker 'PUT' ("vhosts/" + $testVhost) @{}
    $created = $true
    $null = Invoke-Broker 'PUT' ("permissions/$testVhost/taskflow_mq_admin") @{configure='.*';write='.*';read='.*'}
    foreach ($exchange in @('taskflow.dispatch.v1','taskflow.dispatch.dead.v1')) {
        $null = Invoke-Broker 'PUT' ("exchanges/$testVhost/$exchange") @{type='direct';durable=$true;auto_delete=$false;internal=$false;arguments=@{}}
    }
    foreach ($queue in @('taskflow.worker.default.v1','taskflow.worker.default.dead.v1')) {
        $null = Invoke-Broker 'PUT' ("queues/$testVhost/$queue") @{durable=$true;auto_delete=$false;arguments=@{'x-queue-type'='quorum';'x-quorum-initial-group-size'=3}}
    }
    $null = Invoke-Broker 'POST' ("bindings/$testVhost/e/taskflow.dispatch.v1/q/taskflow.worker.default.v1") @{routing_key='worker.default';arguments=@{}}
    $null = Invoke-Broker 'POST' ("bindings/$testVhost/e/taskflow.dispatch.dead.v1/q/taskflow.worker.default.dead.v1") @{routing_key='worker.default.dead';arguments=@{}}
    $null = Invoke-Broker 'PUT' ("policies/$testVhost/dispatch-safety") @{pattern='^taskflow\.worker\.default\.v1$';'apply-to'='quorum_queues';priority=10;
        definition=@{'dead-letter-exchange'='taskflow.dispatch.dead.v1';'dead-letter-routing-key'='worker.default.dead';
            'dead-letter-strategy'='at-least-once';overflow='reject-publish';'delivery-limit'=20}}
    $queues = @(Invoke-Broker 'GET' ("queues/" + $testVhost))
    foreach ($queue in $queues) {
        if ($queue.type -ne 'quorum' -or @($queue.members).Count -ne 3 -or @($queue.online).Count -ne 3) { throw 'Every acceptance queue must have three online quorum replicas.' }
    }
    $queues | Select-Object name,type,members,online | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $runPath 'quorum-before.json')
    Write-TestSettings 15672
    & (Join-Path $PSScriptRoot 'test-execution.ps1') -Transport rabbitmq -MqSettingsPath $settingsPath -SkipBuild:$SkipBuild -SkipBrowser:$SkipBrowser
    if (!$?) { throw 'MQ three-service acceptance failed.' }
    if (!$SkipFailover) {
        # 只停止此仓库 Compose 项目的一个节点；主机上其他服务和 MQ 数据卷不变。
        Invoke-Compose @('stop','rabbit1'); $stopped = $true
        Write-TestSettings 15673
        & (Join-Path $PSScriptRoot 'test-execution.ps1') -Transport rabbitmq -MqSettingsPath $settingsPath -SkipBuild -SkipBrowser
        if (!$?) { throw 'MQ one-node-loss acceptance failed.' }
        Invoke-Compose @('start','rabbit1')
        Invoke-Compose @('exec','-T','rabbit2','rabbitmqctl','await_online_nodes','3','--timeout','90')
        $stopped = $false
    }
    $completed = $true
    Write-Output "MQ acceptance passed. Evidence: $runPath"
} finally {
    try {
        if ($stopped) { Invoke-Compose @('start','rabbit1') }
        # 仅删除本轮随机创建的测试 vhost；失败时保留以便排查，不清空业务队列。
        if ($created -and $completed -and $testVhost -match '^taskflow_it_[a-f0-9]{32}$') { $null = Invoke-Broker 'DELETE' ("vhosts/" + $testVhost) }
    } finally { $client.Dispose() }
}

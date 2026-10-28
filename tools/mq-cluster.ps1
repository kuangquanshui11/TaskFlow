param([ValidateSet('init','up','status','stop')][string]$Action = 'status')
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
$clusterPath = Join-Path $repoPath 'infra/rabbitmq'
$envPath = Join-Path $clusterPath '.env'
$runtimePath = Join-Path $clusterPath 'runtime'
$utf8 = [Text.UTF8Encoding]::new($false)

function New-MqSecret {
    $bytes = New-Object byte[] 32
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}
function Get-RabbitPasswordHash([string]$Password) {
    $salt = New-Object byte[] 4
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($salt) } finally { $rng.Dispose() }
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $digest = $sha.ComputeHash([byte[]]($salt + [Text.Encoding]::UTF8.GetBytes($Password))) } finally { $sha.Dispose() }
    return [Convert]::ToBase64String([byte[]]($salt + $digest))
}
function Invoke-Compose([Parameter(ValueFromRemainingArguments = $true)][string[]]$ComposeArguments) {
    & docker compose --env-file $envPath -f (Join-Path $clusterPath 'compose.yml') @ComposeArguments
    if ($LASTEXITCODE -ne 0) { throw "Docker Compose failed: $($ComposeArguments -join ' ')" }
}
if ($Action -eq 'init') {
    # 不覆盖已有账号、cookie 或本机应用配置，避免意外破坏已初始化的持久卷。
    $schedulerConfig = Join-Path $repoPath 'config/taskflow-scheduler-mq-local.yml'
    $workerConfig = Join-Path $repoPath 'config/taskflow-worker-mq-local.yml'
    foreach ($existing in @($envPath,$schedulerConfig,$workerConfig,(Join-Path $runtimePath 'definitions.json'))) {
        if (Test-Path -LiteralPath $existing) { throw "Already initialized: $existing. Existing credentials were preserved." }
    }
    New-Item -ItemType Directory -Path $runtimePath -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $repoPath 'config') -Force | Out-Null
    $adminPassword = New-MqSecret; $schedulerPassword = New-MqSecret; $workerPassword = New-MqSecret
    $cookie = New-MqSecret
    $users = @(
        @{name='taskflow_mq_admin';password_hash=(Get-RabbitPasswordHash $adminPassword);hashing_algorithm='rabbit_password_hashing_sha256';tags='administrator'},
        @{name='taskflow_scheduler';password_hash=(Get-RabbitPasswordHash $schedulerPassword);hashing_algorithm='rabbit_password_hashing_sha256';tags=''},
        @{name='taskflow_worker';password_hash=(Get-RabbitPasswordHash $workerPassword);hashing_algorithm='rabbit_password_hashing_sha256';tags=''}
    )
    # 账号与 vhost 可在首节点引导；队列必须等三个节点在线后再导入，确保初始即有三个副本。
    $definitions = @{
        users=$users; vhosts=@(@{name='taskflow'})
        permissions=@(
            @{user='taskflow_mq_admin';vhost='taskflow';configure='.*';write='.*';read='.*'},
            @{user='taskflow_scheduler';vhost='taskflow';configure='^$';write='^taskflow\.dispatch\.v1$';read='^$'},
            @{user='taskflow_worker';vhost='taskflow';configure='^$';write='^$';read='^taskflow\.worker\.default\.v1$'}
        )
        exchanges=@(
            @{name='taskflow.dispatch.v1';vhost='taskflow';type='direct';durable=$true;auto_delete=$false;internal=$false;arguments=@{}},
            @{name='taskflow.dispatch.dead.v1';vhost='taskflow';type='direct';durable=$true;auto_delete=$false;internal=$false;arguments=@{}}
        )
        queues=@(
            @{name='taskflow.worker.default.v1';vhost='taskflow';durable=$true;auto_delete=$false;arguments=@{'x-queue-type'='quorum';'x-quorum-initial-group-size'=3}},
            @{name='taskflow.worker.default.dead.v1';vhost='taskflow';durable=$true;auto_delete=$false;arguments=@{'x-queue-type'='quorum';'x-quorum-initial-group-size'=3}}
        )
        bindings=@(
            @{source='taskflow.dispatch.v1';vhost='taskflow';destination='taskflow.worker.default.v1';destination_type='queue';routing_key='worker.default';arguments=@{}},
            @{source='taskflow.dispatch.dead.v1';vhost='taskflow';destination='taskflow.worker.default.dead.v1';destination_type='queue';routing_key='worker.default.dead';arguments=@{}}
        )
        policies=@(@{vhost='taskflow';name='dispatch-safety';pattern='^taskflow\.worker\.default\.v1$';'apply-to'='quorum_queues';priority=10;
            definition=@{'dead-letter-exchange'='taskflow.dispatch.dead.v1';'dead-letter-routing-key'='worker.default.dead';
                'dead-letter-strategy'='at-least-once';'overflow'='reject-publish';'delivery-limit'=20}})
    }
    $topology = @{queues=$definitions.queues;bindings=$definitions.bindings}
    $definitions.queues = @(); $definitions.bindings = @()
    [IO.File]::WriteAllText((Join-Path $runtimePath 'definitions.json'), ($definitions | ConvertTo-Json -Depth 20), $utf8)
    [IO.File]::WriteAllText((Join-Path $runtimePath 'topology.json'), ($topology | ConvertTo-Json -Depth 20), $utf8)
    [IO.File]::WriteAllText($envPath, "TASKFLOW_MQ_COOKIE=$cookie`nTASKFLOW_MQ_ADMIN_PASSWORD=$adminPassword`n", $utf8)
    foreach ($entry in @(@{path=$schedulerConfig;user='taskflow_scheduler';password=$schedulerPassword},@{path=$workerConfig;user='taskflow_worker';password=$workerPassword})) {
        $mqAddresses = '${TASKFLOW_MQ_ADDRESSES:localhost:5672,localhost:5673,localhost:5674}'
        $mqVhost = '${TASKFLOW_MQ_VHOST:taskflow}'
        $mqUsername = '${TASKFLOW_MQ_USERNAME:' + $entry.user + '}'
        $mqPassword = '${TASKFLOW_MQ_PASSWORD:' + $entry.password + '}'
        $mqTransport = '${TASKFLOW_DISPATCH_TRANSPORT:rabbitmq}'
        $yaml = @"
spring:
  rabbitmq:
    addresses: "$mqAddresses"
    virtual-host: "$mqVhost"
    username: "$mqUsername"
    password: "$mqPassword"
taskflow:
  dispatch:
    transport: "$mqTransport"
"@
        [IO.File]::WriteAllText($entry.path, $yaml, $utf8)
    }
    Write-Output 'Created ignored local MQ credentials, definitions and service YAML. No broker has been started.'
    return
}
if (!(Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker with Compose v2 is required. Configuration can be prepared with -Action init without Docker.' }
if (!(Test-Path -LiteralPath $envPath)) { throw 'Run tools/mq-cluster.ps1 -Action init first.' }
switch ($Action) {
    'up' {
        Invoke-Compose @('up','-d','--wait','--wait-timeout','180')
        Invoke-Compose @('exec','-T','rabbit1','rabbitmqctl','await_online_nodes','3','--timeout','60')
        Invoke-Compose @('exec','-T','rabbit1','rabbitmqctl','import_definitions','/etc/rabbitmq/topology.json')
        foreach ($node in @('rabbit1','rabbit2','rabbit3')) {
            Invoke-Compose @('exec','-T',$node,'rabbitmq-queues','grow',("rabbit@" + $node),'all','--vhost-pattern','^taskflow$','--queue-pattern','^taskflow\.worker\.default\.(dead\.)?v1$')
        }
        Invoke-Compose @('exec','-T','rabbit1','rabbitmqctl','list_queues','-p','taskflow','name','type','leader','members','online')
    }
    'status' {
        Invoke-Compose @('ps')
        Invoke-Compose @('exec','-T','rabbit1','rabbitmqctl','cluster_status')
        Invoke-Compose @('exec','-T','rabbit1','rabbitmqctl','list_queues','-p','taskflow','name','type','leader','members','online')
    }
    'stop' { Invoke-Compose @('stop') }
}

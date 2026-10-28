param([Parameter(Mandatory=$true)][string]$ClusterSettingsPath, [switch]$AllowLegacyBroker)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib/mq-management.ps1')
$settings = Read-MqClusterSettings $ClusterSettingsPath
$client = New-MqManagementClient $settings.username $settings.password
$baseUrl = $settings.managementUrls[0]
$runtimePath = Join-Path $repoPath 'infra/rabbitmq/runtime'
$connectionPath = Join-Path $repoPath 'config/taskflow-mq-cluster-local.yml'
try {
    $capability = Get-MqBrokerCapability $client $baseUrl ([bool]$AllowLegacyBroker)
    $nodes = @(Invoke-MqManagement $client $baseUrl 'GET' 'nodes')
    if (@($nodes | Where-Object running).Count -ne 3) { throw 'All three cluster nodes must be online before configuring TaskFlow.' }
    foreach ($file in @('definitions.json','topology.json')) {
        if (!(Test-Path -LiteralPath (Join-Path $runtimePath $file))) { throw 'Initialize local role credentials with tools/mq-cluster.ps1 -Action init first.' }
    }
    # Existing business resources are never overwritten by this one-time attachment command.
    $vhosts = @(Invoke-MqManagement $client $baseUrl 'GET' 'vhosts')
    if ($vhosts.name -contains 'taskflow') { throw 'taskflow vhost already exists; existing business configuration was preserved.' }
    if (Test-Path -LiteralPath $connectionPath) { throw 'Shared local MQ connection YAML already exists; it was preserved.' }
    $definitions = Get-Content -LiteralPath (Join-Path $runtimePath 'definitions.json') -Raw | ConvertFrom-Json
    $topology = Get-Content -LiteralPath (Join-Path $runtimePath 'topology.json') -Raw | ConvertFrom-Json
    $requiredUsers = @('taskflow_mq_admin','taskflow_scheduler','taskflow_worker')
    $users = @(Invoke-MqManagement $client $baseUrl 'GET' 'users')
    if (@($users | Where-Object { $_.name -in $requiredUsers }).Count -gt 0) { throw 'A TaskFlow role already exists; its credentials were preserved.' }
    if (@($definitions.users).Count -ne 3 -or @($definitions.users | Where-Object { $_.name -notin $requiredUsers }).Count -gt 0 -or
        @($definitions.vhosts).Count -ne 1 -or $definitions.vhosts[0].name -ne 'taskflow') { throw 'Unexpected local bootstrap definitions.' }
    $policy = New-MqDispatchSafetyPolicy $capability.legacy
    $definitions.policies[0].definition = $policy.definition
    $definitions.policies[0].'apply-to' = $policy['apply-to']
    $definitions.queues = $topology.queues
    $definitions.bindings = $topology.bindings
    $null = Invoke-MqManagement $client $baseUrl 'POST' 'definitions' $definitions
    $queues = @(Invoke-MqManagement $client $baseUrl 'GET' 'queues/taskflow')
    if ($queues.Count -ne 2) { throw 'Expected exactly two TaskFlow queues.' }
    foreach ($queue in $queues) {
        if ($queue.type -ne 'quorum' -or @($queue.members).Count -ne 3 -or @($queue.online).Count -ne 3) { throw 'TaskFlow queue does not have three online quorum replicas.' }
    }
    $addresses = '${TASKFLOW_MQ_ADDRESSES:' + $settings.addresses + '}'
    $yaml = @"
spring:
  rabbitmq:
    addresses: $($addresses | ConvertTo-Json -Compress)
    virtual-host: "`${TASKFLOW_MQ_VHOST:taskflow}"
taskflow:
  dispatch:
    transport: "`${TASKFLOW_DISPATCH_TRANSPORT:rabbitmq}"
management:
  health:
    rabbit:
      enabled: "`${TASKFLOW_MQ_HEALTH_ENABLED:true}"
"@
    [IO.File]::WriteAllText($connectionPath, $yaml, [Text.UTF8Encoding]::new($false))
    Write-Output "TaskFlow vhost, role permissions and three-replica queues configured on RabbitMQ $($capability.version)."
    Write-Output 'Saved ignored shared connection YAML; per-service role credentials remain in their own ignored MQ YAML. Restart services to apply.'
} finally { $client.Dispose() }

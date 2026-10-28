param(
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-z0-9][a-z0-9_.-]{0,99}$')][string]$WorkerId,
    [ValidatePattern('^[a-zA-Z0-9_.-]{1,100}$')][string]$VirtualHost = 'taskflow',
    [switch]$LegacyBroker
)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'lib/mq-management.ps1')
$outputDirectory = Join-Path $repoPath '.tools/worker-routes'
$outputPath = Join-Path $outputDirectory ($WorkerId + '.json')
if (Test-Path -LiteralPath $outputPath) { throw 'Worker topology file already exists; review the existing file before generating another.' }
$queue = if ($WorkerId -eq 'worker-default') { 'taskflow.worker.default.v1' } else { "taskflow.$WorkerId.v1" }
$routingKey = if ($WorkerId -eq 'worker-default') { 'worker.default' } else { $WorkerId }
$deadQueue = $queue.Substring(0, $queue.Length - 3) + '.dead.v1'
$policy = New-MqDispatchSafetyPolicy ([bool]$LegacyBroker)
$policy.pattern = '^' + [regex]::Escape($queue) + '$'
$policy.definition['dead-letter-routing-key'] = $routingKey + '.dead'
$policy['name'] = 'dispatch-' + $WorkerId
$policy['vhost'] = $VirtualHost
$definitions = @{
    queues = @(
        @{name=$queue;vhost=$VirtualHost;durable=$true;auto_delete=$false;arguments=@{'x-queue-type'='quorum';'x-quorum-initial-group-size'=3}},
        @{name=$deadQueue;vhost=$VirtualHost;durable=$true;auto_delete=$false;arguments=@{'x-queue-type'='quorum';'x-quorum-initial-group-size'=3}}
    )
    bindings = @(
        @{source='taskflow.dispatch.v1';vhost=$VirtualHost;destination=$queue;destination_type='queue';routing_key=$routingKey;arguments=@{}},
        @{source='taskflow.dispatch.dead.v1';vhost=$VirtualHost;destination=$deadQueue;destination_type='queue';routing_key=($routingKey+'.dead');arguments=@{}}
    )
    policies = @($policy)
}
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
[IO.File]::WriteAllText($outputPath, ($definitions | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
Write-Output "Prepared topology: $outputPath"
Write-Output "Worker: $WorkerId; queue: $queue; routing key: $routingKey; dead queue: $deadQueue"
Write-Output 'Review and import with a Broker administrator. This command does not connect to RabbitMQ or change credentials.'

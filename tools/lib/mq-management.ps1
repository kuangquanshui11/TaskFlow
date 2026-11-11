function Read-MqClusterSettings([string]$Path) {
    $settings = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    if (!$settings.addresses -or !$settings.username -or !$settings.password -or @($settings.managementUrls).Count -ne 3) {
        throw 'Cluster settings require addresses, username, password and three managementUrls.'
    }
    foreach ($url in $settings.managementUrls) {
        $uri = [Uri]$url
        if (!$uri.IsAbsoluteUri -or $uri.Scheme -notin @('http','https') -or $uri.UserInfo -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment) {
            throw 'managementUrls must be HTTP(S) origins without credentials, paths or queries.'
        }
    }
    return $settings
}

function New-MqManagementClient([string]$Username, [string]$Password) {
    $http = [Net.Http.HttpClient]::new()
    $http.Timeout = [TimeSpan]::FromSeconds(10)
    $http.DefaultRequestHeaders.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Basic',
        [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Username + ':' + $Password)))
    return $http
}

function Invoke-MqManagement($Client, [string]$BaseUrl, [string]$Method, [string]$Path, $Body = $null) {
    # An uncertain management write is surfaced to the operator; it is never replayed automatically.
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method), ($BaseUrl.TrimEnd('/') + '/api/' + $Path))
    if ($null -ne $Body) {
        $request.Content = [Net.Http.StringContent]::new(($Body | ConvertTo-Json -Depth 40 -Compress), [Text.Encoding]::UTF8, 'application/json')
    }
    try {
        $response = $Client.SendAsync($request).GetAwaiter().GetResult()
        try {
            if (!$response.IsSuccessStatusCode) { throw "RabbitMQ management request failed: $Method $Path HTTP $([int]$response.StatusCode)." }
            $bodyText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if ($bodyText) { return $bodyText | ConvertFrom-Json }
        } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}

function Get-MqBrokerCapability($Client, [string]$BaseUrl, [bool]$AllowLegacy) {
    $overview = Invoke-MqManagement $Client $BaseUrl 'GET' 'overview'
    $match = [regex]::Match($overview.rabbitmq_version, '^\d+\.\d+\.\d+')
    if (!$match.Success) { throw 'Cannot determine RabbitMQ version.' }
    $version = [version]$match.Value
    if ($version -lt [version]'3.8.0') { throw 'Quorum acceptance requires RabbitMQ 3.8 or newer.' }
    $legacy = $version -lt [version]'3.10.0'
    if ($legacy -and !$AllowLegacy) { throw 'RabbitMQ older than 3.10 lacks at-least-once dead lettering. Explicitly use -AllowLegacyBroker for limited compatibility.' }
    if ($legacy) { Write-Warning 'Legacy Broker: dead lettering is at-most-once; reliable dead-letter transfer is NOT accepted.' }
    return [pscustomobject]@{ version=$overview.rabbitmq_version; legacy=$legacy; collectorCompatible=($version -ge [version]'4.3.0'); node=$overview.node }
}

function New-MqDispatchSafetyPolicy([bool]$Legacy) {
    $definition = @{'dead-letter-exchange'='taskflow.dispatch.dead.v1'; 'dead-letter-routing-key'='worker.default.dead';
        overflow='reject-publish'; 'delivery-limit'=20}
    if (!$Legacy) { $definition['dead-letter-strategy'] = 'at-least-once' }
    return @{pattern='^taskflow\.worker\.default\.v1$'; 'apply-to'=$(if ($Legacy) {'queues'} else {'quorum_queues'});
        priority=10; definition=$definition}
}

function New-MqDeadLetterRetentionPolicy([string]$QueuePattern) {
    return @{pattern=$QueuePattern; 'apply-to'='quorum_queues'; priority=10;
        definition=@{'delivery-limit'=-1; overflow='reject-publish'}}
}

function Assert-MqDeadLetterPolicies($Client, [string]$BaseUrl, [string]$VirtualHost, [string]$Source, [string]$Target) {
    $vhost = [Uri]::EscapeDataString($VirtualHost)
    $sourceState = Invoke-MqManagement $Client $BaseUrl 'GET' ("queues/$vhost/" + [Uri]::EscapeDataString($Source))
    $targetState = Invoke-MqManagement $Client $BaseUrl 'GET' ("queues/$vhost/" + [Uri]::EscapeDataString($Target))
    $policy = $sourceState.effective_policy_definition
    if ($sourceState.type -ne 'quorum' -or $targetState.type -ne 'quorum' -or
        $policy.'dead-letter-strategy' -ne 'at-least-once' -or $policy.overflow -ne 'reject-publish' -or
        $policy.'dead-letter-exchange' -ne 'taskflow.dispatch.dead.v1' -or
        $sourceState.arguments.'x-overflow' -eq 'drop-head' -or
        $targetState.effective_policy_definition.'delivery-limit' -ne -1) {
        throw 'Effective queue policy does not preserve at-least-once dead-letter transfer and collector redelivery.'
    }
}

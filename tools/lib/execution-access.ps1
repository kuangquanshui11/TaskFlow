# Sourced by test-execution.ps1; all processes and files belong to this execution's runRoot.
function Set-AcceptanceEnvironment([string]$Name, [string]$Value) {
    if (!$mutatedEnvironment.ContainsKey($Name)) { $mutatedEnvironment[$Name] = [Environment]::GetEnvironmentVariable($Name, 'Process') }
    [Environment]::SetEnvironmentVariable($Name, $Value, 'Process')
}
function Start-AcceptanceRedis([string]$Name) {
    $p = Start-Process -FilePath $script:redisServer -ArgumentList ($Name + '.conf') -WorkingDirectory $runRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runRoot ($Name + '.out.log')) -RedirectStandardError (Join-Path $runRoot ($Name + '.err.log'))
    $accessProcesses[$Name] = $p
    for ($i=0; $i -lt 50; $i++) {
        if ($p.HasExited) { throw "$Name exited; inspect $runRoot" }
        $probe = [Net.Sockets.TcpClient]::new()
        try { if ($probe.ConnectAsync('127.0.0.1', $accessRedisPorts[$Name]).Wait(100)) { return } } catch { }
        finally { $probe.Dispose() }
        Start-Sleep -Milliseconds 100
    }
    throw "$Name startup timed out."
}
function Stop-AcceptanceDependency([string]$Name) {
    if ($accessProcesses.ContainsKey($Name)) {
        $p = $accessProcesses[$Name]
        if (!$p.HasExited) { $p.Kill(); $p.WaitForExit(10000) | Out-Null }
        $p.Dispose(); $accessProcesses.Remove($Name)
    }
}
function Initialize-AcceptanceAccess {
    $script:redisServer = $env:TASKFLOW_TEST_REDIS_SERVER
    if (!$script:redisServer) {
        $command = Get-Command redis-server -ErrorAction SilentlyContinue
        if ($command) { $script:redisServer = $command.Source }
        else {
            $candidate = Get-ChildItem (Join-Path $repoPath '.tools/redis-*/**/redis-server.exe') -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($candidate) { $script:redisServer = $candidate.FullName }
        }
    }
    if (!$script:redisServer -or !(Test-Path -LiteralPath $script:redisServer)) { throw 'Set TASKFLOW_TEST_REDIS_SERVER to a local Redis server executable (Redis 6.2+).' }
    $identityHome = $env:TASKFLOW_TEST_KEYCLOAK_HOME
    if (!$identityHome) { $identityHome = Join-Path $repoPath '.tools/access/keycloak-26.8.1' }
    if (!(Test-Path -LiteralPath (Join-Path $identityHome 'lib/quarkus-run.jar'))) { throw 'Set TASKFLOW_TEST_KEYCLOAK_HOME to an extracted Keycloak distribution.' }
    foreach ($p in @(16379,16380,$IdentityPort)) {
        $probe = [Net.Sockets.TcpClient]::new()
        try { if ($probe.ConnectAsync('127.0.0.1', $p).Wait(150)) { throw "Acceptance dependency port $p is occupied." } }
        catch [System.AggregateException] { } finally { $probe.Dispose() }
    }
    $script:accessSettingsPath = Join-Path $runRoot 'access-settings.json'
    $realm = 'taskflow_it_' + [Guid]::NewGuid().ToString('N')
    $settings = @{ realm=$realm; issuer="http://127.0.0.1:$IdentityPort/realms/$realm"; baseUrl='http://127.0.0.1:8091';
        username='acceptance-admin';password=(New-TestSecret);viewerUsername='acceptance-viewer';viewerPassword=(New-TestSecret);
        sessionPrefix="taskflow:acceptance:$realm`:session:"; rateNamespace="taskflow:acceptance:$realm`:rate";
        redisCli=(Join-Path (Split-Path -Parent $script:redisServer) 'redis-cli.exe');
        sessionRedis=@{port=16379;password=(New-TestSecret)};rateRedis=@{port=16380;password=(New-TestSecret)} }
    [IO.File]::WriteAllText($script:accessSettingsPath, ($settings | ConvertTo-Json -Depth 10), $utf8)
    Set-AcceptanceEnvironment TASKFLOW_TEST_ISSUER $settings.issuer
    Set-AcceptanceEnvironment TASKFLOW_TEST_SESSION_PREFIX $settings.sessionPrefix
    Set-AcceptanceEnvironment TASKFLOW_TEST_RATE_NAMESPACE $settings.rateNamespace
    Set-AcceptanceEnvironment TASKFLOW_ACCESS_SETTINGS $script:accessSettingsPath
    Set-AcceptanceEnvironment TASKFLOW_ACCESS_BASE_URL $settings.baseUrl
    Set-AcceptanceEnvironment TASKFLOW_TEST_SESSION_REDIS_PORT '16379'
    Set-AcceptanceEnvironment TASKFLOW_TEST_RATE_REDIS_PORT '16380'
    Set-AcceptanceEnvironment TASKFLOW_TEST_SESSION_REDIS_PASSWORD $settings.sessionRedis.password
    Set-AcceptanceEnvironment TASKFLOW_TEST_RATE_REDIS_PASSWORD $settings.rateRedis.password
    foreach ($name in @('session-redis','rate-redis')) {
        $password = if ($name -eq 'session-redis') { $settings.sessionRedis.password } else { $settings.rateRedis.password }
        # Separate password-protected processes: no shared database, persistence, or FLUSH commands.
        $config = "bind 127.0.0.1`nport $($accessRedisPorts[$name])`nprotected-mode yes`nrequirepass $password`nsave `"`"`nappendonly no`n"
        [IO.File]::WriteAllText((Join-Path $runRoot ($name + '.conf')), $config, $utf8)
        Start-AcceptanceRedis $name
    }
    $testIdentityHome = Join-Path $runRoot 'identity-home'
    New-Item -ItemType Directory -Path (Join-Path $testIdentityHome 'data/import') -Force | Out-Null
    # Never reuse an installed identity database or modify actual users/realms.
    foreach ($dir in @('lib','conf','providers','themes')) {
        if (Test-Path -LiteralPath (Join-Path $identityHome $dir)) { Copy-Item -LiteralPath (Join-Path $identityHome $dir) -Destination $testIdentityHome -Recurse }
    }
    [IO.File]::WriteAllText((Join-Path $testIdentityHome 'conf/keycloak.conf'), "# Dedicated acceptance instance.`n", $utf8)
    & node (Join-Path $PSScriptRoot '../local/execution-identity.mjs') prepare $script:accessSettingsPath (Join-Path $testIdentityHome 'data/import/realm.json')
    if ($LASTEXITCODE -ne 0) { throw 'Acceptance realm generation failed.' }
    $args = @('-Xms64m','-Xmx384m','--add-opens=java.base/java.lang=ALL-UNNAMED',
        ('"-Dkc.home.dir=' + $testIdentityHome.Replace('\','/') + '"'), ('"-Djboss.server.config.dir=' + (Join-Path $testIdentityHome 'conf').Replace('\','/') + '"'),
        '-cp', ('"' + (Join-Path $testIdentityHome 'lib/quarkus-run.jar') + '"'), 'io.quarkus.bootstrap.runner.QuarkusEntryPoint',
        'start-dev','--http-host=127.0.0.1',('--http-port=' + $IdentityPort),'--db=dev-file',
        ('"--db-url=jdbc:h2:file:' + (Join-Path $testIdentityHome 'data/identity-db').Replace('\','/') + ';NON_KEYWORDS=VALUE"'), '--import-realm')
    $accessProcesses['identity'] = Start-Process -FilePath $javaExe -ArgumentList $args -WorkingDirectory $runRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runRoot 'identity.out.log') -RedirectStandardError (Join-Path $runRoot 'identity.err.log')
    for ($i=0; $i -lt 240; $i++) {
        if ($accessProcesses['identity'].HasExited) { throw "Test identity exited; inspect $runRoot" }
        try {
            $reply = $http.GetAsync($settings.issuer + '/.well-known/openid-configuration').GetAwaiter().GetResult()
            try { if ($reply.IsSuccessStatusCode) { return } } finally { $reply.Dispose() }
        } catch { }
        Start-Sleep -Milliseconds 250
    }
    throw 'Isolated identity readiness timed out.'
}
function Connect-AcceptanceSession {
    $script:accessTokensPath = Join-Path $runRoot 'access-tokens.json'
    & node (Join-Path $PSScriptRoot '../local/execution-identity.mjs') login $script:accessSettingsPath $script:accessTokensPath
    if ($LASTEXITCODE -ne 0) { throw 'Interactive acceptance login failed.' }
    $script:accessTokens = Get-Content -LiteralPath $script:accessTokensPath -Raw | ConvertFrom-Json
    $script:accessExpiresAt = [DateTimeOffset]::UtcNow.AddSeconds($script:accessTokens.expires_in - 30)
    $reply = Call-Api 'POST' 'http://127.0.0.1:19088/api/v1/access/insert' $null '' ('Bearer ' + $script:accessTokens.access_token)
    Assert-Check ($reply.status -eq 200) 'Interactive Keycloak PKCE login establishes a real Redis JWT session through Gateway'
}
function Get-AcceptanceAuthorization {
    if ([DateTimeOffset]::UtcNow -ge $script:accessExpiresAt) {
        $oldToken = $script:accessTokens.access_token
        $settings = Get-Content -LiteralPath $script:accessSettingsPath -Raw | ConvertFrom-Json
        $fresh = Invoke-RestMethod -Method Post -Uri ($settings.issuer + '/protocol/openid-connect/token') -Body @{
            grant_type='refresh_token';client_id='taskflow-console';refresh_token=$script:accessTokens.refresh_token }
        $rotated = Call-Api 'POST' 'http://127.0.0.1:19088/api/v1/access/update' @{previousToken=$oldToken} '' ('Bearer ' + $fresh.access_token)
        if ($rotated.status -ne 200) { throw 'Acceptance JWT session rotation failed; no business POST was replayed.' }
        $script:accessTokens = $fresh
        [IO.File]::WriteAllText($script:accessTokensPath, ($fresh | ConvertTo-Json -Depth 10), $utf8)
        $script:accessExpiresAt = [DateTimeOffset]::UtcNow.AddSeconds($fresh.expires_in - 30)
    }
    return 'Bearer ' + $script:accessTokens.access_token
}

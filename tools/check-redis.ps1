$ErrorActionPreference = 'Stop'
$previousSetting = $env:TASKFLOW_REDIS_CONNECTION_TEST
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $env:TASKFLOW_REDIS_CONNECTION_TEST = 'true'
    & .\mvnw.cmd -pl taskflow-admin '-Dtest=RedisConnectionIntegrationTests' test
    if ($LASTEXITCODE -ne 0) { throw 'Redis connection verification failed.' }
} finally {
    if ($null -eq $previousSetting) { Remove-Item Env:TASKFLOW_REDIS_CONNECTION_TEST -ErrorAction SilentlyContinue }
    else { $env:TASKFLOW_REDIS_CONNECTION_TEST = $previousSetting }
    Pop-Location
}

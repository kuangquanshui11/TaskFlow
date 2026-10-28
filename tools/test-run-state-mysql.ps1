param([ValidateRange(1024,65535)][int]$Port = 13387, [switch]$IncludeDispatchFairness, [switch]$IncludeExecutionLease, [switch]$IncludeWorkerCluster)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoPath
$mysqlExe = (Get-Command mysqld.exe -CommandType Application -ErrorAction Stop).Source
$mysqlBinPath = Split-Path -Parent $mysqlExe
$mysqlBasePath = Split-Path -Parent $mysqlBinPath
$runPath = Join-Path $repoPath ('.tools/run-state-mysql/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$mysqlDataPath = Join-Path $runPath 'data'
New-Item -ItemType Directory -Path $mysqlDataPath -Force | Out-Null
$socketProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
try { $socketProbe.Start() } finally { $socketProbe.Stop() }
$schema = 'taskflow_it_run_state_' + [Guid]::NewGuid().ToString('N')
$variableNames = @('TASKFLOW_RUN_STATE_TEST_DB_URL','TASKFLOW_RUN_STATE_TEST_DB_PORT',
    'TASKFLOW_RUN_STATE_TEST_DB_SCHEMA','TASKFLOW_RUN_STATE_TEST_DB_USERNAME','TASKFLOW_RUN_STATE_TEST_DB_PASSWORD')
$savedVariables = @{}
foreach ($name in $variableNames) { $savedVariables[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$mysqlProcess = $null
try {
    # 空密码仅用于本轮新建且只监听回环地址的临时 MySQL；不读取或复用业务配置。
    & $mysqlExe --no-defaults --initialize-insecure "--basedir=$mysqlBasePath" "--datadir=$mysqlDataPath" "--log-error=$(Join-Path $runPath 'initialize.log')"
    if ($LASTEXITCODE -ne 0) { throw 'Isolated MySQL initialization failed.' }
    $mysqlProcess = Start-Process -FilePath $mysqlExe -ArgumentList @('--no-defaults',
        ('--basedir="' + $mysqlBasePath + '"'), ('--datadir="' + $mysqlDataPath + '"'),
        '--bind-address=127.0.0.1', ('--port=' + $Port), '--mysqlx=0', '--skip-log-bin',
        ('--log-error="' + (Join-Path $runPath 'server.log') + '"')) -WindowStyle Hidden -PassThru
    $ready = $false
    for ($attempt=0; $attempt -lt 60; $attempt++) {
        if ($mysqlProcess.HasExited) { throw 'Isolated MySQL exited during startup.' }
        & (Join-Path $mysqlBinPath 'mysqladmin.exe') --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$Port" --connect-timeout=1 --user=root ping *> $null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Milliseconds 250
    }
    if (!$ready) { throw 'Isolated MySQL readiness timed out.' }
    & (Join-Path $mysqlBinPath 'mysql.exe') --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$Port" --user=root "--execute=CREATE DATABASE $schema CHARACTER SET utf8mb4; SELECT VERSION(), @@transaction_isolation;"
    if ($LASTEXITCODE -ne 0) { throw 'Isolated MySQL schema creation failed.' }
    Remove-Item Env:TASKFLOW_RUN_STATE_TEST_DB_URL -ErrorAction SilentlyContinue
    $env:TASKFLOW_RUN_STATE_TEST_DB_PORT = [string]$Port
    $env:TASKFLOW_RUN_STATE_TEST_DB_SCHEMA = $schema
    $env:TASKFLOW_RUN_STATE_TEST_DB_USERNAME = 'root'
    $env:TASKFLOW_RUN_STATE_TEST_DB_PASSWORD = ''
    # 数据源 URL 与会话隔离级别由 application-test-run-state-mysql.yml 提供。
    $testClasses = if ($IncludeDispatchFairness) { 'RunStateConcurrencyTests,DispatchFairnessTests' } else { 'RunStateConcurrencyTests' }
    if ($IncludeExecutionLease) { $testClasses += ',ExecutionLeaseTests' }
    if ($IncludeWorkerCluster) { $testClasses += ',WorkerClusterDispatchTests' }
    & (Join-Path $repoPath 'mvnw.cmd') -B -l (Join-Path $runPath 'tests.log') -pl taskflow-scheduler ("-Dtest=" + $testClasses) test
    if ($LASTEXITCODE -ne 0) { throw ('Scheduler MySQL tests failed. Evidence: ' + $runPath) }
    Write-Output ('Scheduler MySQL tests passed. Evidence: ' + $runPath)
} finally {
    foreach ($name in $variableNames) { [Environment]::SetEnvironmentVariable($name, $savedVariables[$name], 'Process') }
    if ($mysqlProcess -and !$mysqlProcess.HasExited) {
        & (Join-Path $mysqlBinPath 'mysqladmin.exe') --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$Port" --connect-timeout=2 --user=root shutdown *> $null
        if (!$mysqlProcess.WaitForExit(5000)) { Stop-Process -Id $mysqlProcess.Id }
    }
}

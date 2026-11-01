param([Parameter(Mandatory)][string]$RedisServerPath,[ValidateRange(1024,65535)][int]$Port=16389)
$ErrorActionPreference='Stop'
$repoPath=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoPath
$runPath=Join-Path $repoPath ('.tools/order-redis/'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $runPath -Force | Out-Null
$probe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$Port)
try{$probe.Start()}finally{$probe.Stop()}
$savedPort=$env:TASKFLOW_ORDER_TEST_REDIS_PORT
$process=$null
try {
    $process=Start-Process -FilePath $RedisServerPath -ArgumentList @('--bind','127.0.0.1','--port',$Port,'--save','""','--appendonly','no','--dir',('"'+$runPath+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runPath 'redis.log') -RedirectStandardError (Join-Path $runPath 'redis-error.log')
    $ready=$false
    for($i=0;$i -lt 60;$i++) {
        if($process.HasExited){throw 'Isolated Redis exited'}
        $client=[Net.Sockets.TcpClient]::new()
        try{$client.Connect('127.0.0.1',$Port);$ready=$true;break}catch{Start-Sleep -Milliseconds 200}finally{$client.Dispose()}
    }
    if(!$ready){throw 'Redis readiness timed out'}
    $env:TASKFLOW_ORDER_TEST_REDIS_PORT=[string]$Port
    & (Join-Path $repoPath 'mvnw.cmd') -B -pl examples/taskflow-order-service '-Dtest=StockRedisTests' test -l (Join-Path $runPath 'tests.log')
    if($LASTEXITCODE -ne 0){throw "Redis tests failed: $runPath"}
    Write-Output "Redis tests passed: $runPath"
} finally {
    $env:TASKFLOW_ORDER_TEST_REDIS_PORT=$savedPort
    if($process -and !$process.HasExited){Stop-Process -Id $process.Id;$null=$process.WaitForExit(5000)}
}

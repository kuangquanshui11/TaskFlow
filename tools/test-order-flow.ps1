param([ValidateRange(1024,65530)][int]$BasePort=19380)
$ErrorActionPreference='Stop'
$repoPath=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoPath
if($env:TASKFLOW_ORDER_TEST_DB_SCHEMA -notmatch '^taskflow_it_order_[a-zA-Z0-9]+$') { throw 'Run through test-order-mysql.ps1 -IncludeFlow to use an isolated database.' }
$runPath=Join-Path $repoPath ('.tools/order-flow/'+(Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Path $runPath -Force | Out-Null
$java=(Get-Command java -CommandType Application | Select-Object -First 1).Source
$node=(Get-Command node -CommandType Application | Select-Object -First 1).Source
$h2=Get-ChildItem (Join-Path $env:USERPROFILE '.m2/repository/com/h2database/h2/*/h2-*.jar') | Where-Object Name -NotMatch 'sources|javadoc' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$processes=@{}
$ports=@{scheduler=$BasePort;worker=$BasePort+1;order=$BasePort+2;proxy=$BasePort+3}
foreach($port in $ports.Values) { $probe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$port);try{$probe.Start()}finally{$probe.Stop()} }
$saved=@{}
function Set-TestEnvironment([string]$Name,$Value) {
    if(!$saved.ContainsKey($Name)){$saved[$Name]=[Environment]::GetEnvironmentVariable($Name,'Process')}
    if($null -eq $Value) { Remove-Item ('Env:'+$Name) -ErrorAction SilentlyContinue }
    else { [Environment]::SetEnvironmentVariable($Name,[string]$Value,'Process') }
}
function Secret { return [Guid]::NewGuid().ToString('N')+[Guid]::NewGuid().ToString('N') }
$control=Secret;$dispatch=Secret;$report=Secret;$nodeToken=Secret;$api=Secret;$orderToken=Secret
$http=[Net.Http.HttpClient]::new();$http.Timeout=[TimeSpan]::FromSeconds(5)
$evidence=[Collections.Generic.List[object]]::new()
function Api([string]$Service,[string]$Method,[string]$Path,$Body=$null) {
    $token=if($Service -eq 'order'){$api}else{$control}
    $request=[Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::new($Method),"http://127.0.0.1:$($ports[$Service])/$Path")
    $null=$request.Headers.TryAddWithoutValidation('X-Taskflow-Token',$token)
    if($null -ne $Body){$request.Content=[Net.Http.StringContent]::new(($Body|ConvertTo-Json -Depth 25 -Compress),[Text.Encoding]::UTF8,'application/json')}
    try {
        $response=$http.SendAsync($request).GetAwaiter().GetResult()
        try {
            $bodyText=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if(!$response.IsSuccessStatusCode){throw "API $Service $Path HTTP $([int]$response.StatusCode): $bodyText"}
            return ($bodyText|ConvertFrom-Json)
        } finally {$response.Dispose()}
    } finally {$request.Dispose()}
}
function Wait-For([scriptblock]$Condition,[string]$Description,[int]$Seconds=80) {
    $deadline=[DateTime]::UtcNow.AddSeconds($Seconds)
    do { if(& $Condition){return};Start-Sleep -Milliseconds 200 } while([DateTime]::UtcNow -lt $deadline)
    throw "Timed out: $Description. Evidence: $runPath"
}
function Stop-Owned([string]$Name) {
    if($processes.ContainsKey($Name)) {
        $process=$processes[$Name]
        if(!$process.HasExited){$process.Kill();$null=$process.WaitForExit(10000)}
        $process.Dispose();$processes.Remove($Name)
    }
}
function Start-Service([string]$Name) {
    Set-TestEnvironment 'SERVER_PORT' ([string]$ports[$Name])
    Set-TestEnvironment 'TASKFLOW_ORDER_FLOW_DB_PATH' ((Join-Path $runPath "$Name-db").Replace('\','/'))
    $acceptanceProfile=if($Name -eq 'order'){'business'}else{'platform'}
    $yaml=(Join-Path $PSScriptRoot "acceptance/application-order-flow-$acceptanceProfile.yml").Replace('\','/')
    $jarSource=if($Name -eq 'order'){Join-Path $repoPath 'examples/taskflow-order-service/target/taskflow-order-service-0.0.1-SNAPSHOT.jar'}else{Join-Path $repoPath "taskflow-$Name/target/taskflow-$Name-0.0.1-SNAPSHOT.jar"}
    $jar=Join-Path $runPath "$Name.jar"
    if(!(Test-Path -LiteralPath $jar)){Copy-Item -LiteralPath $jarSource -Destination $jar}
    $stamp=Get-Date -Format 'HHmmssfff'
    $arguments=@('-Xms64m','-Xmx256m',('"-Dloader.path='+$h2.FullName+'"'),('"-Dspring.config.additional-location=file:'+$yaml+'"'),'-cp',('"'+$jar+'"'),'org.springframework.boot.loader.launch.PropertiesLauncher')
    $processes[$Name]=Start-Process -FilePath $java -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runPath "$Name-$stamp.out.log") -RedirectStandardError (Join-Path $runPath "$Name-$stamp.err.log")
    Wait-For {
        if($processes[$Name].HasExited){throw "$Name exited during startup. Evidence: $runPath"}
        try{(Invoke-WebRequest "http://127.0.0.1:$($ports[$Name])/actuator/health" -TimeoutSec 2).StatusCode -eq 200}catch{return $false}
    } "$Name health"
}
function Start-Proxy([string]$Mode) {
    Stop-Owned 'proxy'
    $script=Join-Path $PSScriptRoot 'local/order-fault-proxy.mjs'
    $marker=Join-Path $runPath "$Mode.json"
    $processes['proxy']=Start-Process -FilePath $node -ArgumentList @(('"'+$script+'"'),$ports.proxy,$ports.order,$Mode,('"'+$marker+'"')) -WindowStyle Hidden -PassThru
    Wait-For {try{$client=[Net.Sockets.TcpClient]::new();$client.Connect('127.0.0.1',$ports.proxy);$client.Dispose();return $true}catch{return $false}} 'proxy listening' 10
}
function New-Orders([long]$Sku,[int]$Count) {
    $null=Api order POST 'api/v1/inventory/insert' @{skuId=$Sku;available=$Count}
    $ids=@();for($i=0;$i -lt $Count;$i++){$order=Api order POST 'api/v1/orders/insert' @{requestKey=[Guid]::NewGuid().ToString('N');skuId=$Sku;quantity=1};$ids+=$order.id}
    Start-Sleep -Seconds 3
    return $ids
}
function New-Run {
    return Api scheduler POST 'internal/v1/runs/insert' @{requestKey=[Guid]::NewGuid().ToString();snapshot=@{jobId=999;configVersion=1;name='Order acceptance';handlerKey='orderTimeoutCheck';payload=@{batchSize=2;maxBatches=10};timeoutSeconds=20;maxAttempts=3;retryIntervalSeconds=10;scheduleType='MANUAL';timeZone='UTC';misfirePolicy='SKIP';enabled=$false}}
}
function Await-Run($Run) {
    Wait-For {$script:latestRun=Api scheduler GET "internal/v1/runs/selectById?id=$($Run.id)";return $script:latestRun.state -in @('SUCCEEDED','FAILED')} 'run terminal' 100
    if($script:latestRun.state -ne 'SUCCEEDED'){throw "Run failed: $($script:latestRun|ConvertTo-Json -Depth 20 -Compress)"}
}
function Assert-Stock([long]$Sku,[int]$Expected,$Ids,[string]$Name) {
    $stock=Api order GET "api/v1/inventory/select?skuId=$Sku"
    if($stock.available -ne $Expected -or $stock.reserved -ne 0){throw "Wrong stock: $Name"}
    foreach($id in $Ids){$detail=Api order GET "api/v1/orders/select?id=$id";if($detail.order.state -ne 'CLOSED' -or @($detail.ledger|Where-Object operation -eq 'RELEASED').Count -ne 1){throw "Invalid order ledger: $id"}}
    $evidence.Add(@{name=$Name;result='PASS';orders=$Ids;stock=$stock;run=$script:latestRun})
    Write-Output "PASS $Name"
}
try {
    # 覆盖常见全局启动变量，避免复用本机业务数据和配置。
    foreach($name in @('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','SPRING_DATASOURCE_DRIVER_CLASS_NAME','SPRING_CONFIG_IMPORT','SPRING_PROFILES_ACTIVE','SPRING_CONFIG_ADDITIONAL_LOCATION')){Set-TestEnvironment $name $null}
    Set-TestEnvironment 'TASKFLOW_CONTROL_TOKEN' $control;Set-TestEnvironment 'TASKFLOW_DISPATCH_TOKEN' $dispatch
    Set-TestEnvironment 'TASKFLOW_REPORT_TOKEN' $report;Set-TestEnvironment 'TASKFLOW_NODE_TOKEN' $nodeToken
    Set-TestEnvironment 'TASKFLOW_ORDER_API_TOKEN' $api;Set-TestEnvironment 'TASKFLOW_ORDER_WORKER_TOKEN' $orderToken
    Set-TestEnvironment 'TASKFLOW_SCHEDULER_URL' "http://127.0.0.1:$($ports.scheduler)"
    Set-TestEnvironment 'TASKFLOW_WORKER_URL' "http://127.0.0.1:$($ports.worker)"
    Set-TestEnvironment 'TASKFLOW_ORDER_URL' "http://127.0.0.1:$($ports.proxy)"
    Start-Proxy pass;Start-Service scheduler;Start-Service worker;Start-Service order
    Wait-For {$nodes=Api scheduler GET 'internal/v1/workers/select';return @($nodes.records|Where-Object {$_.handlerKeys -contains 'orderTimeoutCheck'}).Count -gt 0} 'order handler registration'
    $ids=New-Orders 810001 7;$run=New-Run;Await-Run $run;Assert-Stock 810001 7 $ids 'multiple batches'
    $rerun=New-Run;Await-Run $rerun;Assert-Stock 810001 7 $ids 'manual rerun idempotency'
    $ids=New-Orders 810002 3;Stop-Owned order;$run=New-Run
    Wait-For {$r=Api scheduler GET "internal/v1/runs/selectById?id=$($run.id)";return @($r.attempts|Where-Object state -eq 'FAILED').Count -gt 0} 'persisted failed attempt' 30
    Start-Service order;Await-Run $run;Assert-Stock 810002 3 $ids 'service outage retry'
    $ids=New-Orders 810003 5;Start-Proxy drop;$run=New-Run;Await-Run $run
    if(!(Test-Path -LiteralPath (Join-Path $runPath 'drop.json'))){throw 'Lost response was not injected'}
    if(@($script:latestRun.attempts).Count -lt 2){throw 'Lost POST response did not create a persisted retry; check implicit transport replay'}
    Assert-Stock 810003 5 $ids 'committed response lost'
    $ids=New-Orders 810004 7;Start-Proxy hang;$run=New-Run
    Wait-For {Test-Path -LiteralPath (Join-Path $runPath 'hang.json')} 'first batch committed'
    Stop-Owned worker;Start-Proxy pass;Start-Service worker;Await-Run $run
    Assert-Stock 810004 7 $ids 'worker exit after committed batch'
} finally {
    [IO.File]::WriteAllText((Join-Path $runPath 'evidence.json'),($evidence|ConvertTo-Json -Depth 30))
    foreach($name in @('worker','scheduler','order','proxy')){Stop-Owned $name}
    foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
    $http.Dispose()
    Write-Output "Order process evidence: $runPath"
}

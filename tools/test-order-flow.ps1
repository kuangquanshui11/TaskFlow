param([ValidateRange(1024,65530)][int]$BasePort=19380, [switch]$IncludeInventorySync, [string]$CleanupMqSettingsPath)
$ErrorActionPreference='Stop'
$repoPath=Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repoPath
if($env:TASKFLOW_ORDER_TEST_DB_SCHEMA -notmatch '^taskflow_it_order_[a-zA-Z0-9]+$') { throw 'Run through test-order-mysql.ps1 with the relevant option to use an isolated database.' }
$cleanupMq=$null
if($CleanupMqSettingsPath) {
    if($IncludeInventorySync -or $env:TASKFLOW_ORDER_TEST_DB_PORT -notmatch '^\d{4,5}$' -or $env:TASKFLOW_ORDER_TEST_DB_PORT -eq '3306') {
        throw 'Cleanup MQ acceptance requires its own temporary MySQL instance and test mode.'
    }
    $cleanupMq=Get-Content -LiteralPath $CleanupMqSettingsPath -Raw | ConvertFrom-Json
    if($cleanupMq.virtualHost -notmatch '^taskflow_it_[a-f0-9]{32}$' -or !$cleanupMq.addresses) {
        throw 'Cleanup MQ acceptance requires a random test vhost and explicit addresses.'
    }
}
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
$cleanupToken=Secret
$http=[Net.Http.HttpClient]::new();$http.Timeout=[TimeSpan]::FromSeconds(5)
$evidence=[Collections.Generic.List[object]]::new()
function Api([string]$Service,[string]$Method,[string]$Path,$Body=$null) {
    $token=if($Service -ne 'order'){$control}elseif($Path.StartsWith('internal/v1/inventory-sync/')){$orderToken}
        elseif($Path.StartsWith('internal/v1/expired-data/')){$cleanupToken}else{$api}
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
    if($cleanupMq -and $Name -in @('scheduler','worker')) {
        $role=if($cleanupMq.serviceCredentials) {$cleanupMq.serviceCredentials.$Name} else {$cleanupMq}
        if(!$role.username -or !$role.password){throw "Missing test vhost credentials for $Name."}
        Set-TestEnvironment 'TASKFLOW_MQ_USERNAME' $role.username
        Set-TestEnvironment 'TASKFLOW_MQ_PASSWORD' $role.password
    }
    Set-TestEnvironment 'SERVER_PORT' ([string]$ports[$Name])
    Set-TestEnvironment 'TASKFLOW_ORDER_FLOW_DB_PATH' ((Join-Path $runPath "$Name-db").Replace('\','/'))
    $acceptanceProfile=if($Name -eq 'order'){'business'}else{'platform'}
    $yaml=(Join-Path $PSScriptRoot "acceptance/application-order-flow-$acceptanceProfile.yml").Replace('\','/')
    $jarSource=if($Name -eq 'order'){Join-Path $repoPath 'examples/taskflow-order-service/target/taskflow-order-service-0.0.1-SNAPSHOT.jar'}else{Join-Path $repoPath "taskflow-$Name/target/taskflow-$Name-0.0.1-SNAPSHOT.jar"}
    $jar=Join-Path $runPath "$Name.jar"
    if(!(Test-Path -LiteralPath $jar)){Copy-Item -LiteralPath $jarSource -Destination $jar}
    $stamp=Get-Date -Format 'HHmmssfff'
    $arguments=@('-Xms64m','-Xmx256m',('"-Dloader.path='+$h2.FullName+'"'),('"-Dspring.config.additional-location=file:'+$yaml+'"'),'-cp',('"'+$jar+'"'),'org.springframework.boot.loader.launch.PropertiesLauncher')
    $processes[$Name]=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $runPath -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runPath "$Name-$stamp.out.log") -RedirectStandardError (Join-Path $runPath "$Name-$stamp.err.log")
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
function New-Run([string]$Handler='orderTimeoutCheck',$Payload=@{batchSize=2;maxBatches=10}) {
    return Api scheduler POST 'internal/v1/runs/insert' @{requestKey=[Guid]::NewGuid().ToString();snapshot=@{jobId=999;configVersion=1;name='Order acceptance';handlerKey=$Handler;payload=$Payload;timeoutSeconds=20;maxAttempts=3;retryIntervalSeconds=10;scheduleType='MANUAL';timeZone='UTC';misfirePolicy='SKIP';enabled=$false}}
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
function Write-InventorySnapshot([long]$Version,[long]$Quantity) {
    $snapshot=@{warehouseId=$inventoryWarehouse;sourceVersion=$Version;items=@(@{skuId=12001;quantity=$Quantity})} | ConvertTo-Json -Depth 4
    [IO.File]::WriteAllText($inventorySource,$snapshot)
}
function Test-InventorySync {
    Wait-For {
        $nodes=Api scheduler GET 'internal/v1/workers/select'
        return @($nodes.records | Where-Object {$_.handlerKeys -contains 'inventorySync'}).Count -gt 0
    } 'inventory handler registration'
    Start-Proxy drop
    $run=New-Run inventorySync @{warehouseId=$inventoryWarehouse}
    Await-Run $run
    $applied=$script:latestRun
    $dropped=Get-Content -LiteralPath (Join-Path $runPath 'drop.json') -Raw | ConvertFrom-Json
    if($dropped.result.outcome -ne 'APPLIED' -or @($applied.attempts).Count -ne 2 -or
            $applied.attempts[0].state -ne 'FAILED' -or $applied.message -notlike "batchId=$($dropped.result.id),*") {
        throw 'Inventory retry did not return the first committed batch through a second Attempt.'
    }
    Await-Run (New-Run inventorySync @{warehouseId=$inventoryWarehouse})
    $unchanged=$script:latestRun
    if($unchanged.id -eq $applied.id -or $unchanged.message -notlike '*sourceVersion=1, outcome=UNCHANGED, updatedCount=0') {
        throw 'A new Run for the same inventory version should record an unchanged batch.'
    }
    Write-InventorySnapshot 3 17
    $replayed=Api order POST 'internal/v1/inventory-sync/insert' @{warehouseId=$inventoryWarehouse;requestKey="$($run.id):inventory-sync"}
    if($replayed.id -ne $dropped.result.id -or $replayed.sourceVersion -ne 1){throw 'Inventory request key did not preserve the original batch.'}
    Await-Run (New-Run inventorySync @{warehouseId=$inventoryWarehouse})
    $advanced=$script:latestRun
    if($advanced.message -notlike '*sourceVersion=3, outcome=APPLIED, updatedCount=1'){throw 'Inventory version 3 was not applied.'}
    Write-InventorySnapshot 2 99
    Await-Run (New-Run inventorySync @{warehouseId=$inventoryWarehouse})
    $stale=$script:latestRun
    $stock=Api order GET "internal/v1/inventory-sync/select?warehouseId=$inventoryWarehouse&skuId=12001"
    if($stale.message -notlike '*sourceVersion=2, outcome=STALE, updatedCount=0' -or $stock.sourceVersion -ne 3 -or $stock.quantity -ne 17){
        throw 'Late snapshot modified the newer inventory.'
    }
    $evidence.Add(@{name='inventory sync retries and source versions';result='PASS';applied=$applied;unchanged=$unchanged;replayed=$replayed;advanced=$advanced;stale=$stale;stock=$stock})
    Write-Output 'PASS inventory sync retries and source versions'
}
function Invoke-CleanupSql([string]$Sql) {
    $mysql=(Get-Command mysql.exe -CommandType Application).Source
    $previousPassword=$env:MYSQL_PWD
    try {
        $env:MYSQL_PWD=$env:TASKFLOW_ORDER_TEST_DB_PASSWORD
        & $mysql --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$env:TASKFLOW_ORDER_TEST_DB_PORT" `
            "--database=$env:TASKFLOW_ORDER_TEST_DB_SCHEMA" "--user=$env:TASKFLOW_ORDER_TEST_DB_USERNAME" --batch --skip-column-names "--execute=SET time_zone = '+00:00'; $Sql"
        if($LASTEXITCODE -ne 0){throw 'Cleanup fixture MySQL command failed.'}
    } finally { [Environment]::SetEnvironmentVariable('MYSQL_PWD',$previousPassword,'Process') }
}
function Test-Cleanup {
    Wait-For {
        $nodes=Api scheduler GET 'internal/v1/workers/select'
        return @($nodes.records | Where-Object {
            $_.dispatchEligible -and $_.dispatchTransport -eq 'rabbitmq' -and $_.handlerKeys -contains 'expiredDataCleanup'
        }).Count -eq 1
    } 'eligible RabbitMQ cleanup Worker'
    Invoke-CleanupSql @'
INSERT INTO stock_outbox_history (id, sku_id, published_at) VALUES
    ('expired-first', 801, CURRENT_TIMESTAMP(6) - INTERVAL 101 DAY),
    ('expired-second', 802, CURRENT_TIMESTAMP(6) - INTERVAL 91 DAY),
    ('near-cutoff', 803, CURRENT_TIMESTAMP(6) - INTERVAL 90 DAY + INTERVAL 1 HOUR),
    ('recent', 804, CURRENT_TIMESTAMP(6));
INSERT INTO stock_outbox (id, sku_id, created_at, attempts, next_attempt_at) VALUES
    ('pending', 805, CURRENT_TIMESTAMP(6) - INTERVAL 100 DAY, 0, CURRENT_TIMESTAMP(6));
'@
    $payload=@{retentionDays=90;batchSize=1;maxBatches=1;dryRun=$true}
    Await-Run (New-Run expiredDataCleanup $payload)
    $preview=$script:latestRun
    $initialRows=@(Invoke-CleanupSql 'SELECT COUNT(*) FROM stock_outbox_history; SELECT COUNT(*) FROM expired_data_cleanup_batch;')
    if($preview.message -notlike 'mode=PREVIEW,*eligibleCount=1, deletedCount=0, hasMore=true' -or ($initialRows -join ',') -ne '4,0') {
        throw 'Cleanup preview did not preserve all history and batch receipts.'
    }
    $evidence.Add(@{name='RabbitMQ cleanup preview is read-only';result='PASS';run=$preview})
    Write-Output 'PASS RabbitMQ cleanup preview is read-only'

    $payload.dryRun=$false
    Start-Proxy drop
    $manual=New-Run expiredDataCleanup $payload
    Await-Run $manual
    $retried=$script:latestRun
    $dropped=(Get-Content -LiteralPath (Join-Path $runPath 'drop.json') -Raw | ConvertFrom-Json).result
    $afterRetry=@(Invoke-CleanupSql 'SELECT id FROM stock_outbox_history ORDER BY id; SELECT COUNT(*) FROM expired_data_cleanup_batch;')
    if(@($retried.attempts).Count -ne 2 -or $retried.attempts[0].state -ne 'FAILED' -or
        $dropped.deletedCount -ne 1 -or !$dropped.hasMore -or
        $retried.message -notmatch '^mode=DELETE, cutoff=([^,]+), deletedCount=1, batches=1, hasMore=true$' -or
        [DateTimeOffset]$Matches[1] -ne [DateTimeOffset]$dropped.cutoff -or
        ($afterRetry -join ',') -ne 'expired-second,near-cutoff,recent,1') {
        throw 'Same Run retry did not replay its first committed cleanup batch.'
    }
    $evidence.Add(@{name='RabbitMQ cleanup replays a committed batch after response loss';result='PASS';run=$retried;committed=$dropped})
    Write-Output 'PASS RabbitMQ cleanup replays a committed batch after response loss'

    $configuration=@{name='Cleanup Cron acceptance';handlerKey='expiredDataCleanup';payloadJson=($payload|ConvertTo-Json -Compress);
        scheduleType='CRON';cronExpression='*/8 * * * * *';timeZone='Asia/Shanghai';misfirePolicy='SKIP';
        timeoutSeconds=20;maxAttempts=1;retryIntervalSeconds=1;enabled=$true}
    $plan=@{eventId=[Guid]::NewGuid().ToString();jobId=1001;configVersion=1;deleted=$false;configuration=$configuration}
    $null=Api scheduler POST 'internal/v1/plans/update' $plan
    $cron=$null
    try {
        Wait-For {
            $page=Api scheduler GET 'internal/v1/runs/selectGlobal?jobId=1001&triggerType=CRON'
            $script:cleanupCron=$page.records | Select-Object -First 1
            return $null -ne $script:cleanupCron
        } 'automatic cleanup Run' 25
        $cron=$script:cleanupCron
    } finally {
        $plan.eventId=[Guid]::NewGuid().ToString();$plan.configVersion=2;$configuration.enabled=$false
        $null=Api scheduler POST 'internal/v1/plans/update' $plan
    }
    Await-Run $cron
    $scheduled=$script:latestRun
    $remaining=@(Invoke-CleanupSql 'SELECT id FROM stock_outbox_history ORDER BY id; SELECT id FROM stock_outbox; SELECT COUNT(*) FROM expired_data_cleanup_batch;')
    if(!$scheduled.scheduledAt -or $scheduled.triggerType -ne 'CRON' -or
        $scheduled.message -notlike 'mode=DELETE,*deletedCount=1, batches=1, hasMore=false' -or
        ($remaining -join ',') -ne 'near-cutoff,recent,pending,2') {
        throw 'Scheduled cleanup did not preserve near-cutoff, recent and pending data.'
    }
    $evidence.Add(@{name='RabbitMQ Cron cleanup preserves retained and pending rows';result='PASS';run=$scheduled;remaining=$remaining})
    Write-Output 'PASS RabbitMQ Cron cleanup preserves retained and pending rows'
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
    Set-TestEnvironment 'TASKFLOW_INVENTORY_SYNC_SOURCE_ENABLED' ($IncludeInventorySync.ToString().ToLowerInvariant())
    Set-TestEnvironment 'TASKFLOW_INVENTORY_SYNC_HANDLER_ENABLED' ($IncludeInventorySync.ToString().ToLowerInvariant())
    if($cleanupMq) {
        foreach($name in @('SPRING_APPLICATION_JSON','SPRING_CONFIG_LOCATION','SPRING_RABBITMQ_ADDRESSES',
            'SPRING_RABBITMQ_HOST','SPRING_RABBITMQ_PORT','SPRING_RABBITMQ_VIRTUALHOST','SPRING_RABBITMQ_USERNAME','SPRING_RABBITMQ_PASSWORD',
            'TASKFLOW_MQ_EXCHANGE','TASKFLOW_MQ_QUEUE','TASKFLOW_MQ_ROUTING_KEY','TASKFLOW_MQ_DLX','TASKFLOW_MQ_DLQ','TASKFLOW_MQ_DEAD_ROUTING_KEY')) {
            Set-TestEnvironment $name $null
        }
        Set-TestEnvironment 'TASKFLOW_EXPIRED_DATA_CLEANUP_SOURCE_ENABLED' 'true'
        Set-TestEnvironment 'TASKFLOW_EXPIRED_DATA_CLEANUP_HANDLER_ENABLED' 'true'
        Set-TestEnvironment 'TASKFLOW_EXPIRED_DATA_CLEANUP_TOKEN' $cleanupToken
        Set-TestEnvironment 'TASKFLOW_DISPATCH_TRANSPORT' 'rabbitmq'
        Set-TestEnvironment 'TASKFLOW_MQ_ADDRESSES' $cleanupMq.addresses
        Set-TestEnvironment 'TASKFLOW_MQ_VHOST' $cleanupMq.virtualHost
        Set-TestEnvironment 'TASKFLOW_PLAN_TRIGGER_ENABLED' 'true'
        Set-TestEnvironment 'MANAGEMENT_HEALTH_RABBIT_ENABLED' 'true'
    }
    if($IncludeInventorySync) {
        $inventoryWarehouse=120
        $inventorySource=Join-Path $runPath 'inventory-source.json'
        Write-InventorySnapshot 1 80
        Set-TestEnvironment 'TASKFLOW_INVENTORY_SYNC_SAMPLE_SOURCE' ([Uri]$inventorySource).AbsoluteUri
    }
    Start-Proxy pass;Start-Service scheduler;Start-Service worker;Start-Service order
    if($cleanupMq) { Test-Cleanup; return }
    if($IncludeInventorySync) {
        Test-InventorySync
        return
    }
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

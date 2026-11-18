function New-ExpiredPayment([int]$Sku) {
    $null = Api order POST 'internal/v1/sales-inputs/products/update' @{
        skuId=$Sku; shopId=1; name="Race SKU $Sku"; unitPriceCents=250; catalogSource='test'; catalogVersion=1
    }
    $null = Api order POST 'api/v1/inventory/insert' @{skuId=$Sku; available=1}
    $order = Api order POST 'api/v1/orders/insert' @{skuId=$Sku; quantity=1; requestKey=[Guid]::NewGuid().ToString('N')}
    $payment = Api order POST 'api/v1/order-payments/insertNative' @{orderId=$order.id}
    if ($order.id -cnotmatch '^[a-f0-9-]{36}$') { throw 'Invalid test order ID' }
    # Shorten only the fixture deadline after creating the native intent under the real two-minute minimum.
    & (Join-Path $mysqlBin 'mysql.exe') --no-defaults --protocol=TCP --host=127.0.0.1 "--port=$($ports.mysql)" --user=root `
        "--database=$($vhost)_order" "--execute=UPDATE purchase_order SET expires_at=created_at + INTERVAL 2 SECOND WHERE id='$($order.id)'"
    if ($LASTEXITCODE -ne 0) { throw 'Could not shorten the test order deadline' }
    Wait-For { @(Db order "SELECT id FROM purchase_order WHERE id='$($order.id)' AND expires_at<CURRENT_TIMESTAMP(6)").Count -eq 1 } 'Test order expired'
    $paidAt = ([DateTimeOffset]$order.createdAt).AddSeconds(1).ToString('o')
    $payload = & $node (Join-Path $PSScriptRoot '../local/order-payment-notification.mjs') notify $payment.tradeNo ([string]$Sku) $paidAt
    if ($LASTEXITCODE -ne 0) { throw 'Could not sign payment notification' }
    return @{orderId=$order.id; payload=($payload | ConvertFrom-Json)}
}

function New-NotificationRequest($Payload) {
    $request = Request order POST 'api/v1/order-payments/updateWechatNotification'
    $request.Content = [Net.Http.StringContent]::new($Payload.body, [Text.Encoding]::UTF8, 'application/json')
    $request.Headers.Add('Wechatpay-Serial','PUB_KEY_ID_123456789')
    $request.Headers.Add('Wechatpay-Timestamp',$Payload.timestamp)
    $request.Headers.Add('Wechatpay-Nonce',$Payload.nonce)
    $request.Headers.Add('Wechatpay-Signature',$Payload.signature)
    return $request
}

function Assert-Notification($Pending) {
    $response = $Pending.GetAwaiter().GetResult()
    try {
        if ([int]$response.StatusCode -ne 204) {
            throw "Test payment notification returned HTTP $([int]$response.StatusCode): $($response.Content.ReadAsStringAsync().GetAwaiter().GetResult())"
        }
    } finally { $response.Dispose() }
}

function Invoke-OrderPaymentRace([int]$Sku, [bool]$PaymentFirst) {
    $label = if ($PaymentFirst) { 'payment-first' } else { 'close-first' }
    $fixture = New-ExpiredPayment $Sku
    Arm order-row
    [IO.File]::WriteAllText((Join-Path $root 'order-row.arm'), $fixture.orderId)
    Arm confirm
    $request = New-NotificationRequest $fixture.payload
    $release = Join-Path $root 'order-row.release'
    try {
        if ($PaymentFirst) {
            $pending = $http.SendAsync($request)
            Wait-For { Test-Path -LiteralPath (Join-Path $root 'order-row.json') } 'Payment holds order row' 10
        }
        $run = Api scheduler POST 'internal/v1/runs/insert' (Run-Body ([Guid]::NewGuid().ToString()))
        if (!$PaymentFirst) {
            Wait-For { Test-Path -LiteralPath (Join-Path $root 'order-row.json') } 'MQ close holds order row' 10
            $pending = $http.SendAsync($request)
        }
        Wait-For { Test-Path -LiteralPath (Join-Path $root 'confirm.json') } 'Original Broker ACK held' 10
        $sql = "SELECT requested.lock_data, waits.requesting_thread_id, waits.blocking_thread_id " +
            "FROM performance_schema.data_lock_waits waits JOIN performance_schema.data_locks requested " +
            "ON requested.engine_lock_id=waits.requesting_engine_lock_id AND requested.engine=waits.engine " +
            "WHERE requested.object_schema='$($vhost)_order' AND requested.object_name='purchase_order' " +
            "AND requested.lock_data LIKE '%$($fixture.orderId)%'"
        Wait-For { @(Db order $sql).Count -eq 1 } 'The other request waits on the same InnoDB row' 10
        $waiters = @(Db order $sql)
        $before = @(Db scheduler "SELECT * FROM dispatch_outbox WHERE attempt_id='$($run.attempts[0].id)'")
        Json "$label-race" @{
            time=[DateTimeOffset]::Now.ToString('o'); orderId=$fixture.orderId; runId=$run.id; winner=$label;
            lock=(Get-Content (Join-Path $root 'order-row.json') -Raw | ConvertFrom-Json); waiters=$waiters;
            confirm=(Get-Content (Join-Path $root 'confirm.json') -Raw | ConvertFrom-Json); outbox=$before
        }
        Check ($waiters.Count -eq 1 -and $before.Count -eq 1 -and $before[0].state -eq 'SENDING' -and
            $before[0].publish_attempts -eq 1 -and !$pending.IsCompleted) "$label`: both requests compete while the initial publish is unconfirmed"
        $cut = Join-Path $root 'confirm.cut'
        $cutDone = "$cut.done"
        if (Test-Path -LiteralPath $cutDone) { Remove-Item -LiteralPath $cutDone }
        [IO.File]::WriteAllText($cut, 'Drop the acceptance publisher connection with its held confirm')
        Wait-For { Test-Path -LiteralPath $cutDone } 'Test publisher connection cut' 5
        Wait-For { @(Db scheduler "SELECT event_id FROM dispatch_outbox WHERE event_id='$($before[0].event_id)' AND state='PUBLISHED' AND publish_attempts=2").Count -eq 1 } 'Same event republished' 12
        [IO.File]::WriteAllText($release, 'resume')
        Assert-Notification $pending
    } finally {
        # Release even if an assertion fails so the blocked transactions can exit.
        if (!(Test-Path -LiteralPath $release)) { [IO.File]::WriteAllText($release, 'resume') }
        $request.Dispose()
    }
    $replay = New-NotificationRequest $fixture.payload
    try { Assert-Notification ($http.SendAsync($replay)) } finally { $replay.Dispose() }
    Wait-For { (Api scheduler GET "internal/v1/runs/selectById?id=$($run.id)").state -in @('SUCCEEDED','FAILED') } 'Order Run terminal'
    Wait-For { @((Db worker "SELECT reported FROM worker_execution WHERE run_id='$($run.id)'") | Where-Object reported).Count -eq 1 } 'Order Inbox reported'
    # Both iterations share a fresh queue, so the cumulative delivery and ack counts are exact.
    $expectedDeliveries = if ($PaymentFirst) { 2 } else { 4 }
    Wait-For {
        $queue = Broker GET "queues/$vhost/taskflow.worker.default.v1"
        $queue.messages_ready -eq 0 -and $queue.messages_unacknowledged -eq 0 -and
            $queue.message_stats.deliver -eq $expectedDeliveries -and $queue.message_stats.ack -eq $expectedDeliveries
    } 'Broker delivered and acknowledged both copies'
    $final = Snapshot "$label-dispatch" $run.id $Sku
    $timeline = Api scheduler GET "internal/v1/runs/selectAttemptEvents?runId=$($run.id)&attemptId=$($final.attempts[0].id)"
    $payment = Api order GET "api/v1/order-payments/select?orderId=$($fixture.orderId)"
    $reservation = @(Db order "SELECT * FROM stock_reservation WHERE order_id='$($fixture.orderId)'")
    $paymentRows = @(Db order ("SELECT p.*, p.paid_at < o.expires_at AS paid_before_deadline FROM order_payment p " +
        "JOIN purchase_order o ON o.id=p.order_id WHERE p.order_id='$($fixture.orderId)'"))
    $paymentOutbox = @(Db order "SELECT * FROM order_payment_outbox WHERE order_id='$($fixture.orderId)' ORDER BY kind")
    Json "$label-business" @{payment=$payment; paymentRows=$paymentRows; paymentOutbox=$paymentOutbox; reservation=$reservation; timeline=$timeline}
    Queue-Snapshot "$label-broker-drained" 0
    $command = ($before[0].message_json | ConvertFrom-Json).command
    Check ($final.run.Count -eq 1 -and $final.run[0].state -eq 'SUCCEEDED' -and $final.attempts.Count -eq 1 -and
        $final.attempts[0].state -eq 'SUCCEEDED' -and $final.attempts[0].lease_version -eq $command.leaseVersion -and
        $final.outbox.Count -eq 1 -and $final.outbox[0].event_id -eq $before[0].event_id -and
        $final.outbox[0].message_json -ceq $before[0].message_json -and $final.outbox[0].state -eq 'PUBLISHED' -and
        $final.outbox[0].publish_attempts -eq 2 -and $final.inbox.Count -eq 1 -and $final.inbox[0].reported -and
        $final.inbox[0].attempt_id -eq $final.attempts[0].id -and $final.inbox[0].state -eq 'SUCCEEDED' -and
        @($timeline.worker.events | Where-Object eventType -eq 'HANDLER_STARTED').Count -eq 1 -and
        @($timeline.scheduler.events | Where-Object eventType -eq 'MQ_PUBLISH_UNCONFIRMED').Count -eq 1) "$label`: original event republished and handled once"

    $refunds = @($paymentOutbox | Where-Object kind -eq 'REFUND')
    $summary = $final.run[0].message
    $expected = if ($PaymentFirst) {
        $final.orders[0].state -eq 'PAID' -and $payment.paymentState -eq 'PAID' -and $payment.refundState -eq 'NONE' -and
            $refunds.Count -eq 0 -and $final.stock[0].available -eq 0 -and $reservation[0].state -eq 'CONSUMED' -and
            @($final.ledger | Where-Object operation -eq 'CONSUMED').Count -eq 1 -and $summary -eq 'scanned=1, closed=0, skipped=1, failed=0'
    } else {
        $final.orders[0].state -eq 'CLOSED' -and $payment.paymentState -eq 'LATE_PAID' -and $payment.refundState -eq 'PENDING' -and
            $payment.refundNo -eq ('late-' + $payment.tradeNo) -and $refunds.Count -eq 1 -and $refunds[0].state -eq 'READY' -and
            $final.stock[0].available -eq 1 -and $reservation[0].state -eq 'RELEASED' -and
            @($final.ledger | Where-Object operation -eq 'RELEASED').Count -eq 1 -and $summary -eq 'scanned=1, closed=1, skipped=0, failed=0'
    }
    Check ($expected -and $paymentRows.Count -eq 1 -and $paymentRows[0].transaction_id -eq [string]$Sku -and
        $paymentRows[0].amount_cents -eq 250 -and $paymentRows[0].paid_before_deadline -eq 1 -and
        $final.stock[0].reserved -eq 0 -and $reservation.Count -eq 1 -and
        $final.ledger.Count -eq 2 -and @($final.ledger | Where-Object operation -eq 'HELD').Count -eq 1) "$label`: one stock settlement and the expected payment/refund state after replay"
}

function Invoke-OrderPaymentRaces {
    $null = Api order POST 'internal/v1/sales-inputs/shops/insert' @{shopId=1; name='Fault acceptance'}
    Invoke-OrderPaymentRace 41101 $true
    Invoke-OrderPaymentRace 41102 $false
}

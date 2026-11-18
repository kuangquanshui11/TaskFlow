function Wait-WorkerReportStale([string]$RunId) {
    Wait-For {
        @(Db worker "SELECT event_type FROM worker_attempt_event WHERE run_id='$RunId' AND event_type='REPORT_STALE'").Count -eq 1
    } 'Scheduler fences the delayed report'
}

function Wait-RunLeaseExpiry([string]$Id) {
    Wait-For {
        $run = Api scheduler GET "internal/v1/runs/selectById?id=$Id"
        $run.state -eq 'FAILED' -and $run.resultCode -eq 'LEASE_EXPIRED'
    } 'Scheduler expires the isolated Worker lease'
}

function Invoke-WorkerLinkFault {
    $block = Join-Path $root 'worker-link.block'
    $beforePid = $processes.worker.Id
    $slow = Run-Body ([Guid]::NewGuid().ToString())
    $slow.snapshot.handlerKey = 'demoSlow'
    $slow.snapshot.payload = @{durationMillis=60000}
    $run = Api scheduler POST 'internal/v1/runs/insert' $slow
    Wait-For {
        @(Db worker "SELECT id FROM worker_attempt_event WHERE run_id='$($run.id)' AND event_type='HANDLER_STARTED'").Count -eq 1
    } 'Running Handler started before network cut'
    [IO.File]::WriteAllText($block,'partition')
    Json worker-active-link-cut @{time=[DateTimeOffset]::Now.ToString('o');runId=$run.id;workerPid=$beforePid}
    Wait-RunLeaseExpiry $run.id
    $before = Snapshot worker-active-before-heal $run.id 41004
    $nodeBefore = Api scheduler GET 'internal/v1/workers/selectById?workerId=worker-default'
    Check ($before.attempts.Count -eq 1 -and $before.attempts[0].state -eq 'TIMED_OUT' -and
        $before.inbox.Count -eq 1 -and $before.inbox[0].state -eq 'FAILED' -and
        $before.inbox[0].result_code -eq 'LEASE_LOST' -and !$before.inbox[0].reported -and
        $nodeBefore.status -eq 'OFFLINE') 'The running Handler stops on lease loss and the disconnected Worker becomes offline'
    Remove-Item -LiteralPath $block
    Wait-WorkerReportStale $run.id
    Wait-For {
        (Api scheduler GET 'internal/v1/workers/selectById?workerId=worker-default').status -eq 'ONLINE'
    } 'Worker registration recovers after connectivity returns'
    $after = Snapshot worker-active-after-heal $run.id 41004
    Check ($after.run[0].state -eq 'FAILED' -and $after.run[0].result_code -eq 'LEASE_EXPIRED' -and
        $after.attempts.Count -eq 1 -and $after.attempts[0].lease_version -eq $before.attempts[0].lease_version -and
        $after.inbox[0].reported -and $processes.worker.Id -eq $beforePid -and !$processes.worker.HasExited) 'Reconnect preserves the authoritative timeout and settles the stale report without restarting Worker'

    $null = Seed 41005
    [IO.File]::WriteAllText((Join-Path $root 'worker-result.arm'),'block first result')
    $run = Api scheduler POST 'internal/v1/runs/insert' (Run-Body ([Guid]::NewGuid().ToString()))
    Wait-For { Test-Path -LiteralPath (Join-Path $root 'worker-result.hit') } 'Cut the Worker link at its first result request'
    Wait-RunLeaseExpiry $run.id
    $before = Snapshot worker-committed-before-heal $run.id 41005
    Check ($before.inbox.Count -eq 1 -and $before.inbox[0].state -eq 'SUCCEEDED' -and !$before.inbox[0].reported -and
        $before.orders.Count -eq 1 -and $before.orders[0].state -eq 'CLOSED' -and $before.stock[0].reserved -eq 0 -and
        $before.stock[0].available -eq 1 -and @($before.ledger | Where-Object operation -eq 'RELEASED').Count -eq 1) 'Business commit is visible even though Scheduler could not confirm execution success'
    Remove-Item -LiteralPath $block
    Wait-WorkerReportStale $run.id
    $after = Snapshot worker-committed-after-heal $run.id 41005
    $events = @(Db worker "SELECT event_type FROM worker_attempt_event WHERE run_id='$($run.id)' AND event_type='HANDLER_STARTED'")
    Check ($after.run[0].state -eq 'FAILED' -and $after.run[0].result_code -eq 'LEASE_EXPIRED' -and
        $after.attempts.Count -eq 1 -and $after.inbox[0].reported -and $events.Count -eq 1 -and
        ($before.ledger | ConvertTo-Json -Compress -Depth 8) -ceq ($after.ledger | ConvertTo-Json -Compress -Depth 8)) 'Late success leaves the timeout unchanged and does not repeat business side effects'
}

function Read-ClusterViews {
    for ($index=0; $index -lt 3; $index++) {
        $url = $cluster.managementUrls[$index]
        try {
            $identity = Invoke-MqManagement $management $url GET overview
            $peers = @(Invoke-MqManagement $management $url GET nodes)
            [pscustomobject]@{index=$index;node=$identity.node;peers=@($peers | Select-Object name,running,partitions)}
        } catch {
            [pscustomobject]@{index=$index;node=$null;peers=@()}
        }
    }
}

function Test-MissingPeers($View, [string[]]$ExpectedNames) {
    if (!$View.node -or @($View.peers).Count -ne 3 -or @($View.peers.name | Sort-Object -Unique).Count -ne 3) { return $false }
    $local = $View.peers | Where-Object name -eq $View.node
    if (!$local.running) { return $false }
    $missing = @($View.peers | Where-Object { !$_.running } | ForEach-Object name)
    $missing += @($local.partitions | Where-Object { $_ })
    $observed = @($missing | Sort-Object -Unique)
    return (($observed -join ',') -ceq (@($ExpectedNames | Sort-Object -Unique) -join ','))
}

function Wait-HealthyTestCluster([string[]]$Names) {
    Wait-For {
        $views = @(Read-ClusterViews)
        if (@($views | Where-Object { !$_.node -or !(Test-MissingPeers $_ @()) }).Count -gt 0 -or
            (@($views | ForEach-Object node) -join ',') -cne ($Names -join ',')) { return $false }
        try { $queues = @(Broker GET "queues/$vhost") } catch { return $false }
        if ($queues.Count -ne 2 -or @($queues | Where-Object { @($_.online).Count -ne 3 }).Count -gt 0) { return $false }
        Json publisher-topology-healed @{time=[DateTimeOffset]::Now.ToString('o');views=$views;
            queues=@($queues | Select-Object name,leader,members,online)}
        return $true
    } 'Same three nodes are connected again and quorum replicas recovered' 180
    $script:topologyRecoveryPending = $false
}

function Invoke-PublisherAvailabilityFault {
    $proxyOnly = $Scenario -like 'Publisher*'
    $exhaust = $Scenario -like '*Exhausted'
    $partition = $Scenario -eq 'BrokerPartition'
    $marker = Join-Path $root 'publisher.block'
    $sku = 41006
    $null = Seed $sku
    Arm before-publish
    $run = Api scheduler POST 'internal/v1/runs/insert' (Run-Body ([Guid]::NewGuid().ToString()))
    Wait-Gate before-publish
    $before = Snapshot publisher-before $run.id $sku
    Check ($before.outbox.Count -eq 1 -and $before.outbox[0].state -eq 'SENDING' -and
        $before.outbox[0].publish_attempts -eq 1 -and $before.inbox.Count -eq 0) 'One committed Outbox is paused before the first network publish'
    $id = $before.outbox[0].event_id
    if ($proxyOnly) {
        [IO.File]::WriteAllText($marker,'disconnect only the isolated Publisher')
    } else {
        $views = @(Read-ClusterViews)
        $names = @($views | ForEach-Object node)
        Check ($names.Count -eq 3 -and @($names | Where-Object { $_ } | Sort-Object -Unique).Count -eq 3 -and
            @($views | Where-Object { !(Test-MissingPeers $_ @()) }).Count -eq 0) 'Three direct healthy Broker identities'
        Check (@(Broker GET connections | Where-Object vhost -ne $vhost).Count -eq 0) 'Broker fault scope has only this test vhost connected'
        $script:topologyRecoveryPending = $true
        if ($partition) {
            Write-Host "PARTITION NOW: isolate $($names[0]) from $($names[1]), $($names[2]) bidirectionally at peer links. Preserve AMQP and management access."
        } else {
            Write-Host "STOP NODES NOW: $($names[1]), $($names[2]). Keep $($names[0]) running."
        }
        Wait-For {
            if ($partition) {
                $split = @(Read-ClusterViews)
                if (!(Test-MissingPeers $split[0] $names[1..2]) -or !(Test-MissingPeers $split[1] @($names[0])) -or
                    !(Test-MissingPeers $split[2] @($names[0]))) { return $false }
                try {
                    $majorityQueues = @(Invoke-MqManagement $management $cluster.managementUrls[1] GET "queues/$vhost")
                    if ($majorityQueues.Count -ne 2 -or @($majorityQueues | Where-Object {
                        @($_.online).Count -ne 2 -or @($_.online | Where-Object { $_ -in $names[1..2] }).Count -ne 2 -or
                        $_.leader -notin $names[1..2]
                    }).Count -gt 0) { return $false }
                } catch { return $false }
                Json publisher-topology-fault @{time=[DateTimeOffset]::Now.ToString('o');views=$split;
                    majorityQueues=@($majorityQueues | Select-Object name,leader,members,online)}
            } else {
                # Stopped nodes must lose their direct management endpoint and be down in the survivor's view.
                $down = @(Read-ClusterViews)
                if ($down[1].node -or $down[2].node -or $down[0].node -ne $names[0] -or
                    !(Test-MissingPeers $down[0] $names[1..2])) { return $false }
                Json publisher-topology-fault @{time=[DateTimeOffset]::Now.ToString('o');views=$down}
            }
            return $true
        } 'Observed the requested 2+1 partition or loss of two Broker nodes' 180
    }
    [IO.File]::WriteAllText((Join-Path $root 'before-publish.release'),'resume publish under the fault')

    if ($exhaust) {
        Wait-For {
            $current = Api scheduler GET "internal/v1/runs/selectById?id=$($run.id)"
            $current.state -eq 'FAILED' -and $current.resultCode -eq 'MQ_PUBLISH_UNCONFIRMED'
        } 'The publish retry budget is exhausted with an explicit failure'
        $failed = Snapshot publisher-exhausted $run.id $sku
        Check ($failed.attempts.Count -eq 1 -and $failed.inbox.Count -eq 0 -and $failed.outbox.Count -eq 1 -and
            $failed.outbox[0].event_id -eq $id -and $failed.outbox[0].state -eq 'FAILED' -and
            $failed.outbox[0].last_error_code -and $failed.outbox[0].publish_attempts -eq 3 -and
            $failed.orders[0].state -eq 'PENDING' -and @($failed.ledger | Where-Object operation -eq 'RELEASED').Count -eq 0) 'Exhaustion leaves a traceable failure with no business change'
    } else {
        Wait-For {
            $row = @(Db scheduler "SELECT state,last_error_code,publish_attempts FROM dispatch_outbox WHERE event_id='$id'")
            if ($row.Count -ne 1 -or $row[0].state -ne 'READY' -or !$row[0].last_error_code -or
                $row[0].publish_attempts -ge 3) { return $false }
            Json publisher-unconfirmed @{time=[DateTimeOffset]::Now.ToString('o');eventId=$id;runId=$run.id;outbox=$row[0]}
            return $true
        } 'Publish was unconfirmed and scheduled for retry within its budget'
        Check $true 'Publisher did not record a confirm during the fault and kept retry budget'
    }
    if ($proxyOnly) {
        Remove-Item -LiteralPath $marker
    } else {
        Write-Host "RESTORE BROKER NOW: restore connectivity or restart the stopped nodes and wait for all three queue replicas."
        Wait-HealthyTestCluster $names
    }
    Json publisher-fault-removed @{time=[DateTimeOffset]::Now.ToString('o');mode=$Scenario;runId=$run.id}
    if ($exhaust) {
        Queue-Snapshot publisher-after-exhaustion-broker 0
        $stable = Snapshot publisher-exhausted-after-restore $run.id $sku
        Check ($stable.run[0].state -eq 'FAILED' -and $stable.inbox.Count -eq 0 -and
            $stable.outbox[0].publish_attempts -eq $failed.outbox[0].publish_attempts -and $stable.orders[0].state -eq 'PENDING' -and
            @($stable.ledger | Where-Object operation -eq 'RELEASED').Count -eq 0) 'Restoring availability does not silently replay an exhausted Run'
        # This isolated scenario can prove zero prior effects. A real unknown result requires reconciliation before rerun.
        $new = Api scheduler POST 'internal/v1/runs/insert' (Run-Body ([Guid]::NewGuid().ToString()))
        Check ($new.id -ne $run.id) 'After reconciliation, an explicit new request creates a new Run'
        $null = Finish publisher-manual-recovery $new.id $sku
    } else {
        $after = Finish publisher-recovered $run.id $sku
        Check ($after.outbox[0].event_id -eq $id -and $after.outbox[0].message_json -ceq $before.outbox[0].message_json -and
            $after.outbox[0].publish_attempts -gt 1 -and $after.attempts[0].lease_version -eq $before.attempts[0].lease_version) 'Recovery resends the original event and preserves the Attempt identity'
    }
}

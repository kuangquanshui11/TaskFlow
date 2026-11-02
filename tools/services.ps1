param(
    [ValidateSet('start','stop','restart','status')][string]$Action = 'status',
    [ValidateSet('registry','config-server','gateway','admin','scheduler','worker','order','all')][string]$Service = 'all',
    [switch]$Standalone
)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
$runtimeRoot = Join-Path $repoPath '.tools/runtime'
$registryFile = Join-Path $repoPath '.tools/taskflow-runtime.json'
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
$registry = @{}
if (Test-Path -LiteralPath $registryFile) {
    $saved = Get-Content -LiteralPath $registryFile -Raw -Encoding utf8 | ConvertFrom-Json
    foreach ($property in $saved.PSObject.Properties) { $registry[$property.Name] = $property.Value }
}
function Save-Registry { $registry | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $registryFile -Encoding utf8 }
function Read-Health([int]$Port) {
    $handler = [Net.Http.HttpClientHandler]::new()
    $handler.UseProxy = $false
    $client = [Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(2)
    try { return ($client.GetStringAsync("http://127.0.0.1:$Port/actuator/health").GetAwaiter().GetResult() | ConvertFrom-Json) }
    finally { $client.Dispose() }
}
function Find-OwnedProcess($Entry) {
    if (!$Entry) { return $null }
    $running = Get-CimInstance Win32_Process -Filter ("ProcessId = " + $Entry.processId)
    if (!$running) { return $null }
    if (!$running.CommandLine.Contains($Entry.jar)) { throw 'Recorded PID now belongs to another process; refusing to manage it.' }
    return $running
}
function Stop-Owned([string]$Name) {
    if (!$registry.ContainsKey($Name)) { return }
    $running = Find-OwnedProcess $registry[$Name]
    if ($running) {
        Stop-Process -Id $running.ProcessId
        Wait-Process -Id $running.ProcessId -Timeout 10 -ErrorAction SilentlyContinue
    }
    $registry.Remove($Name); Save-Registry
    Write-Output ("Stopped taskflow-" + $Name)
}
function Start-Owned([string]$Name) {
    if ($registry.ContainsKey($Name) -and (Find-OwnedProcess $registry[$Name])) {
        if ($registry[$Name].port -gt 0) { Write-Output ("taskflow-" + $Name + " already running."); return }
        Stop-Owned $Name
    }
    $module = if ($Name -eq 'order') { 'examples/taskflow-order-service' } else { "taskflow-$Name" }
    $artifact = if ($Name -eq 'order') { 'taskflow-order-service' } else { "taskflow-$Name" }
    $source = Join-Path $repoPath ("$module/target/$artifact-0.0.1-SNAPSHOT.jar")
    if (!(Test-Path -LiteralPath $source)) { throw 'Build the service with mvnw.cmd verify first.' }
    $tag = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $jar = Join-Path $runtimeRoot ("taskflow-$Name-$tag.jar")
    $stdout = Join-Path $runtimeRoot ("taskflow-$Name-$tag.out.log")
    $stderr = Join-Path $runtimeRoot ("taskflow-$Name-$tag.err.log")
    Copy-Item -LiteralPath $source -Destination $jar
    $java = (Get-Command java -CommandType Application).Source
    $javaArguments=@('-jar',('"'+$jar+'"'))
    if(!$Standalone -and $Name -in @('gateway','admin','scheduler','worker','order')) { $javaArguments+='--spring.profiles.active=cloud' }
    $oldAdminUrl=[Environment]::GetEnvironmentVariable('TASKFLOW_ADMIN_URL','Process')
    if($Name -eq 'gateway' -and $registry.ContainsKey('admin') -and $registry['admin'].port -gt 0 -and !$oldAdminUrl) {
        $env:TASKFLOW_ADMIN_URL='http://127.0.0.1:'+$registry['admin'].port
    }
    try { $process = Start-Process -FilePath $java -ArgumentList $javaArguments -WorkingDirectory $repoPath -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr }
    finally {
        if($null -eq $oldAdminUrl) { Remove-Item Env:TASKFLOW_ADMIN_URL -ErrorAction SilentlyContinue }
        else { $env:TASKFLOW_ADMIN_URL=$oldAdminUrl }
    }
    $entry = [pscustomobject]@{ processId = $process.Id; jar = $jar; stdout = $stdout; stderr = $stderr; port = 0 }
    $registry[$Name] = $entry; Save-Registry
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) { throw ("taskflow-" + $Name + " exited. Inspect " + $stdout) }
        $stream = [IO.File]::Open($stdout, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        $reader = [IO.StreamReader]::new($stream)
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $match = [regex]::Match($text, '(?:Tomcat|Netty) started on port (\d+)')
        if ($match.Success) {
            $entry.port = [int]$match.Groups[1].Value
            try {
                $health = Read-Health $entry.port
                if ($health.status -eq 'UP') {
                    Save-Registry
                    if ($Name -eq 'admin') { $entry | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $repoPath '.tools/admin-runtime.json') -Encoding utf8 }
                    Write-Output ("taskflow-" + $Name + " UP, port=" + $entry.port + ", pid=" + $entry.processId)
                    return
                }
            } catch { }
        }
        Start-Sleep -Milliseconds 250
    }
    throw ("taskflow-" + $Name + " readiness timed out. Inspect " + $stdout)
}
$names = if ($Service -eq 'all') { @('registry','config-server','scheduler','worker','admin','gateway') } else { @($Service) }
if ($Service -eq 'all' -and ($registry.ContainsKey('order') -or (Test-Path (Join-Path $repoPath 'config/taskflow-order-local.yml')))) {
    $names = @('registry','config-server','order','scheduler','worker','admin','gateway')
}
if($Action -eq 'stop' -and $Service -eq 'all') { [array]::Reverse($names) }
foreach ($name in $names) {
    switch ($Action) {
        'start' { Start-Owned $name }
        'stop' { Stop-Owned $name }
        'restart' { Stop-Owned $name; Start-Owned $name }
        'status' {
            if ($registry.ContainsKey($name) -and (Find-OwnedProcess $registry[$name])) {
                $entry = $registry[$name]
                $status = 'UNREACHABLE'
                try {
                    $health = Read-Health $entry.port
                    $status = $health.status
                } catch { }
                [pscustomobject]@{ service = $name; processId = $entry.processId; port = $entry.port; health = $status }
            } else { [pscustomobject]@{ service = $name; health = 'STOPPED' } }
        }
    }
}

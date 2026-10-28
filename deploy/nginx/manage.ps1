param([ValidateSet('start', 'stop', 'reload', 'test', 'status')][string]$Action = 'status')
$ErrorActionPreference = 'Stop'
$bundlePath = (Resolve-Path -LiteralPath $PSScriptRoot).Path
$nginxExe = Join-Path $bundlePath 'nginx.exe'
$nginxPrefix = $bundlePath.Replace('\', '/') + '/'
$nginxArgs = @('-p', $nginxPrefix, '-c', 'conf/nginx.conf')
if ($Action -eq 'status') {
    $pidFile = Join-Path $bundlePath 'logs/nginx.pid'
    if (Test-Path -LiteralPath $pidFile) {
        $nginxProcessId = 0
        [void][int]::TryParse([IO.File]::ReadAllText($pidFile).Trim(), [ref]$nginxProcessId)
        $running = Get-Process -Id $nginxProcessId -ErrorAction SilentlyContinue
        if ($running -and $running.Path -eq $nginxExe) { Write-Output "Nginx running (PID $nginxProcessId)"; exit 0 }
    }
    Write-Output 'Nginx stopped'; exit 0
}
if ($Action -in @('test', 'start', 'reload')) {
    & $nginxExe @nginxArgs -t
    if ($LASTEXITCODE -ne 0) { throw 'Nginx configuration check failed.' }
}
if ($Action -eq 'start') {
    $pidFile = Join-Path $bundlePath 'logs/nginx.pid'
    if (Test-Path -LiteralPath $pidFile) {
        $nginxProcessId = 0
        [void][int]::TryParse([IO.File]::ReadAllText($pidFile).Trim(), [ref]$nginxProcessId)
        $running = Get-Process -Id $nginxProcessId -ErrorAction SilentlyContinue
        if ($running -and $running.Path -eq $nginxExe) { Write-Output 'This Nginx instance is already running.'; exit 0 }
    }
    # Use a hidden helper; the Nginx master and worker remain in the background.
    Start-Process -FilePath $nginxExe -ArgumentList @('-p', ('"' + $nginxPrefix + '"'), '-c', 'conf/nginx.conf') -WorkingDirectory $bundlePath -WindowStyle Hidden
    $started = $false
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        Start-Sleep -Milliseconds 100
        if (!(Test-Path -LiteralPath $pidFile)) { continue }
        $nginxProcessId = 0
        [void][int]::TryParse([IO.File]::ReadAllText($pidFile).Trim(), [ref]$nginxProcessId)
        if ($nginxProcessId -le 0) { continue }
        $running = Get-Process -Id $nginxProcessId -ErrorAction SilentlyContinue
        if ($running -and $running.Path -eq $nginxExe) { $started = $true; break }
    }
    if (!$started) { throw 'Nginx did not start; check logs/error.log and the listen port.' }
    Write-Output 'Nginx started. See README.txt for the local URL.'
} elseif ($Action -eq 'reload') {
    & $nginxExe @nginxArgs -s reload
    if ($LASTEXITCODE -ne 0) { throw 'Nginx reload failed.' }
} elseif ($Action -eq 'stop') {
    & $nginxExe @nginxArgs -s quit
    if ($LASTEXITCODE -ne 0) { throw 'Nginx graceful stop failed.' }
}

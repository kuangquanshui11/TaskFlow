param(
    [ValidateRange(1024, 65535)][int]$HttpPort = 8090,
    [ValidatePattern('^[a-zA-Z0-9.-]+$')][string]$GatewayHost = '127.0.0.1',
    [ValidateRange(1, 65535)][int]$GatewayPort = 8088,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
$webPath = Join-Path $repoPath 'taskflow-web'
$nginxVersion = '1.31.8'
$archiveHash = 'FBB1501009BE34655CC238D460714CF20391B639013F4C73859813D7938783FD'
$toolsPath = Join-Path $repoPath '.tools'
$archivePath = Join-Path $toolsPath "downloads/nginx-$nginxVersion.zip"
$nginxPath = Join-Path $toolsPath "nginx-$nginxVersion"

if (!$SkipBuild) {
    Push-Location $webPath
    try {
        if (!(Test-Path -LiteralPath 'node_modules')) {
            & npm.cmd ci --no-audit --no-fund
            if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
        }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
}
if (!(Test-Path -LiteralPath (Join-Path $webPath 'dist/index.html'))) { throw 'Frontend dist is missing.' }
if (!(Test-Path -LiteralPath $archivePath)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $archivePath) | Out-Null
    Invoke-WebRequest -Uri "https://nginx.org/download/nginx-$nginxVersion.zip" -OutFile $archivePath
}
if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $archiveHash) { throw 'Nginx archive checksum mismatch.' }
if (!(Test-Path -LiteralPath (Join-Path $nginxPath 'nginx.exe'))) { Expand-Archive -LiteralPath $archivePath -DestinationPath $toolsPath -Force }

# Each package gets a fresh directory, so rebuilding never overwrites a running instance.
$releaseId = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$releaseRelative = "releases/$releaseId/taskflow-nginx"
$distPath = Join-Path $repoPath 'dist'
$bundlePath = Join-Path $distPath $releaseRelative
New-Item -ItemType Directory -Force -Path $bundlePath, (Join-Path $bundlePath 'conf'), (Join-Path $bundlePath 'logs'), (Join-Path $bundlePath 'temp') | Out-Null
Copy-Item -LiteralPath (Join-Path $nginxPath 'nginx.exe') -Destination $bundlePath
Copy-Item -LiteralPath (Join-Path $nginxPath 'conf/mime.types') -Destination (Join-Path $bundlePath 'conf')
Copy-Item -LiteralPath (Join-Path $nginxPath 'docs/LICENSE') -Destination (Join-Path $bundlePath 'NGINX-LICENSE.txt')
Copy-Item -LiteralPath (Join-Path $webPath 'dist') -Destination (Join-Path $bundlePath 'html') -Recurse
Copy-Item -LiteralPath (Join-Path $repoPath 'deploy/nginx/manage.ps1') -Destination $bundlePath
$nginxConfig = Get-Content -LiteralPath (Join-Path $repoPath 'deploy/nginx/nginx.conf.template') -Raw
$nginxConfig = $nginxConfig.Replace('__GATEWAY_HOST__', $GatewayHost).Replace('__GATEWAY_PORT__', "$GatewayPort").Replace('__HTTP_PORT__', "$HttpPort")
[IO.File]::WriteAllText((Join-Path $bundlePath 'conf/nginx.conf'), $nginxConfig, [Text.UTF8Encoding]::new($false))
$readmeText = @"
TaskFlow frontend / Nginx $nginxVersion (Windows)

URL: http://localhost:$HttpPort
Gateway API upstream: ${GatewayHost}:$GatewayPort
Start Gateway and its Admin upstream before using API features.
This package contains no database credentials, backend JAR or Redis server.

Open PowerShell in this directory:
  powershell -ExecutionPolicy Bypass -File ./manage.ps1 -Action start
  powershell -ExecutionPolicy Bypass -File ./manage.ps1 -Action status
  powershell -ExecutionPolicy Bypass -File ./manage.ps1 -Action reload
  powershell -ExecutionPolicy Bypass -File ./manage.ps1 -Action stop

Listen is restricted to 127.0.0.1. API authentication and production TLS are not yet implemented.
Edit conf/nginx.conf to change local ports, then test and reload.
Nginx archive SHA256: $archiveHash
Official source: https://nginx.org/download/nginx-$nginxVersion.zip
"@
[IO.File]::WriteAllText((Join-Path $bundlePath 'README.txt'), $readmeText, [Text.UTF8Encoding]::new($false))

$staticPackage = Join-Path $distPath "releases/$releaseId/taskflow-web"
New-Item -ItemType Directory -Force -Path (Join-Path $staticPackage 'conf') | Out-Null
Copy-Item -LiteralPath (Join-Path $bundlePath 'html') -Destination $staticPackage -Recurse
Copy-Item -LiteralPath (Join-Path $bundlePath 'conf/mime.types') -Destination (Join-Path $staticPackage 'conf')
Copy-Item -LiteralPath (Join-Path $bundlePath 'conf/nginx.conf') -Destination (Join-Path $staticPackage 'conf')
New-Item -ItemType Directory -Force -Path (Join-Path $staticPackage 'logs'), (Join-Path $staticPackage 'temp') | Out-Null
$staticReadme = @"
TaskFlow static frontend for an existing Nginx installation

Contents: html/, conf/nginx.conf, conf/mime.types, logs/, temp/
Default URL: http://localhost:$HttpPort
Gateway backend: ${GatewayHost}:$GatewayPort (start separately)

Use an absolute deployment directory as Nginx prefix:
  nginx -p /absolute/deploy/directory/ -c conf/nginx.conf -t
  nginx -p /absolute/deploy/directory/ -c conf/nginx.conf

Adjust listen, root, log paths and upstream for the deployment host.
For a ready-to-run Windows bundle, use taskflow-nginx-windows.zip instead.
No backend credentials or Redis server are included.
"@
[IO.File]::WriteAllText((Join-Path $staticPackage 'README.txt'), $staticReadme, [Text.UTF8Encoding]::new($false))
Compress-Archive -Path (Join-Path $staticPackage '*') -DestinationPath (Join-Path $distPath 'taskflow-web.zip') -Force
Compress-Archive -LiteralPath $bundlePath -DestinationPath (Join-Path $distPath 'taskflow-nginx-windows.zip') -Force
[IO.File]::WriteAllText((Join-Path $distPath 'latest-build.txt'), $releaseRelative)
$zipHashes = Get-FileHash -LiteralPath (Join-Path $distPath 'taskflow-web.zip'), (Join-Path $distPath 'taskflow-nginx-windows.zip') -Algorithm SHA256 | ForEach-Object { "$($_.Hash)  $([IO.Path]::GetFileName($_.Path))" }
$zipHashes | Set-Content -LiteralPath (Join-Path $distPath 'SHA256SUMS.txt') -Encoding ascii
Write-Output "Package ready: $bundlePath"
Write-Output 'To activate: copy dist/latest-build.txt to dist/current-release.txt, then run tools/nginx.ps1 -Action start.'

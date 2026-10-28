param([ValidateSet('start', 'stop', 'reload', 'test', 'status')][string]$Action = 'status')
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
$markerPath = Join-Path $repoPath 'dist/current-release.txt'
if (!(Test-Path -LiteralPath $markerPath)) { throw 'Run tools/package-frontend.ps1 first.' }
$releaseName = (Get-Content -LiteralPath $markerPath -Raw).Trim()
$distPath = [IO.Path]::GetFullPath((Join-Path $repoPath 'dist'))
$bundlePath = [IO.Path]::GetFullPath((Join-Path $distPath $releaseName))
if (!$bundlePath.StartsWith($distPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid release path.' }
& (Join-Path $bundlePath 'manage.ps1') -Action $Action

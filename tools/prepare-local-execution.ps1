$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
Push-Location $repoPath
try {
    $jar = Join-Path $repoPath 'taskflow-admin/target/taskflow-admin-0.0.1-SNAPSHOT.jar'
    if (!(Test-Path -LiteralPath $jar)) { throw 'Build Admin first using mvnw.cmd verify.' }
    $libraries = Join-Path $repoPath '.tools/local-execution-libs'
    New-Item -ItemType Directory -Path $libraries -Force | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($jar)
    $paths = @()
    try {
        foreach ($pattern in @('^BOOT-INF/lib/mysql-connector-j-[0-9].*\.jar$', '^BOOT-INF/lib/snakeyaml-[0-9].*\.jar$')) {
            $entry = $archive.Entries | Where-Object { $_.FullName -match $pattern } | Select-Object -First 1
            if (!$entry) { throw 'Required bundled runtime library missing.' }
            $path = Join-Path $libraries $entry.Name
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $path, $true)
            $paths += $path
        }
    } finally { $archive.Dispose() }
    & java --class-path ($paths -join ';') (Join-Path $PSScriptRoot 'local/ProvisionLocalExecution.java')
    if ($LASTEXITCODE -ne 0) { throw 'Local execution infrastructure preparation failed.' }
    & (Join-Path $PSScriptRoot 'prepare-node-identity.ps1')
} finally { Pop-Location }

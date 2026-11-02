$ErrorActionPreference = 'Stop'
$repoPath = Split-Path -Parent $PSScriptRoot
Push-Location $repoPath
try {
    $jar = Join-Path $repoPath 'taskflow-admin/target/taskflow-admin-0.0.1-SNAPSHOT.jar'
    if (!(Test-Path -LiteralPath $jar)) { throw 'Build Admin first using mvnw.cmd verify.' }
    $libraries = Join-Path $repoPath '.tools/local-execution-libs'
    $classes = Join-Path $repoPath '.tools/sales-tools'
    New-Item -ItemType Directory -Path $libraries,$classes -Force | Out-Null
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
    & javac -encoding UTF-8 -cp ($paths -join ';') -d $classes tools/local/ProvisionLocalExecution.java tools/local/ProvisionLocalSales.java
    if ($LASTEXITCODE -ne 0) { throw 'Local sales preparation tool compilation failed.' }
    & java -cp ((@($classes) + $paths) -join ';') ProvisionLocalSales
    if ($LASTEXITCODE -ne 0) { throw 'Local sales preparation failed.' }
} finally { Pop-Location }

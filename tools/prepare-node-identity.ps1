$ErrorActionPreference='Stop'
$repoPath=Split-Path -Parent $PSScriptRoot
$configPath=Join-Path $repoPath 'config/taskflow-nodes-local.yml'
if (Test-Path -LiteralPath $configPath) { Write-Output 'Existing local node credentials preserved.'; return }
$bytes=New-Object byte[] 32
$rng=[Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
$secret=-join ($bytes | ForEach-Object { $_.ToString('x2') })
$placeholder='${TASKFLOW_NODE_TOKEN:'+$secret+'}'
New-Item -ItemType Directory -Path (Join-Path $repoPath 'config') -Force | Out-Null
$yaml="taskflow:`n  nodes:`n    node-token: `"$placeholder`"`n"
$stream=[IO.File]::Open($configPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
try {
    $encoded=[Text.UTF8Encoding]::new($false).GetBytes($yaml)
    $stream.Write($encoded,0,$encoded.Length)
} finally { $stream.Dispose() }
Write-Output 'Generated an ignored, shared local node token for Scheduler and Worker. No service was started.'

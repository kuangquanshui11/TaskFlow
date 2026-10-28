$ErrorActionPreference='Stop'
$repoPath=Split-Path -Parent $PSScriptRoot
$file=Join-Path $repoPath 'config/taskflow-platform-local.yml'
if(Test-Path -LiteralPath $file) { Write-Output 'Existing registry/config credentials preserved.';return }
function New-PlatformSecret {
    $bytes=New-Object byte[] 32;$rng=[Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}
$registryPassword=New-PlatformSecret;$configPassword=New-PlatformSecret
$registrySecret='${TASKFLOW_REGISTRY_PASSWORD:'+$registryPassword+'}'
$configSecret='${TASKFLOW_CONFIG_PASSWORD:'+$configPassword+'}'
$registryUrl='${TASKFLOW_REGISTRY_URL:http://taskflow_registry:'+$registryPassword+'@localhost:8761/eureka/}'
$yaml=@"
taskflow:
  registry:
    username: taskflow_registry
    password: "$registrySecret"
    url: "$registryUrl"
  config:
    username: taskflow_config
    password: "$configSecret"
"@
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $file) | Out-Null
$stream=[IO.File]::Open($file,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
try { $bytes=[Text.UTF8Encoding]::new($false).GetBytes($yaml);$stream.Write($bytes,0,$bytes.Length) } finally { $stream.Dispose() }
$envFile=Join-Path $repoPath 'deploy/platform/.env'
if(!(Test-Path -LiteralPath $envFile)) {
    [IO.File]::WriteAllText($envFile,"TASKFLOW_REGISTRY_PASSWORD=$registryPassword`nTASKFLOW_CONFIG_PASSWORD=$configPassword`n",[Text.UTF8Encoding]::new($false))
}
Write-Output 'Generated ignored registry/config credentials. No services or databases were changed.'

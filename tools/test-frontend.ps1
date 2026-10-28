param([switch]$SkipBuild)
$ErrorActionPreference='Stop'
# 浏览器验收统一经过 Nginx -> Gateway -> Admin，并使用隔离的业务进程和 H2。
& (Join-Path $PSScriptRoot 'test-execution.ps1') -SkipBuild:$SkipBuild
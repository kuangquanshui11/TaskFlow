param(
    [Parameter(Mandatory=$true)][string]$OrderServiceUrl,
    [string]$Token = $env:TASKFLOW_ORDER_API_TOKEN,
    [string]$Dataset = 'sales-demo-v1',
    [datetime]$StartDate = ([datetime]::Today.AddDays(-30)),
    [ValidateRange(1,90)][int]$Days = 30,
    [ValidateRange(1,10000)][int]$OrderCount = 2000,
    [long]$Seed = 20261102
)
$ErrorActionPreference = 'Stop'
if (!$Token) { throw 'Provide TASKFLOW_ORDER_API_TOKEN or -Token; no token is printed or stored by this script.' }
$body = @{dataset=$Dataset;startDate=$StartDate.ToString('yyyy-MM-dd');days=$Days;orderCount=$OrderCount;seed=$Seed} | ConvertTo-Json
$result = Invoke-RestMethod -Uri ($OrderServiceUrl.TrimEnd('/')+'/api/v1/sales-samples/insert') -Method Post -ContentType 'application/json' -Headers @{'X-Taskflow-Token'=$Token} -Body $body -TimeoutSec 600
$result | Select-Object dataset,startDate,days,orderCount,seed

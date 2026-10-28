param([Parameter(Mandatory=$true)][string]$TraceDirectory,[Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{32}$')][string]$TraceId)
$ErrorActionPreference='Stop'
$traceBytes=New-Object byte[] 16
for($i=0;$i -lt 16;$i++) { $traceBytes[$i]=[Convert]::ToByte($TraceId.Substring($i*2,2),16) }
$encoding=[Text.Encoding]::GetEncoding(28591)
$needle=$encoding.GetString($traceBytes)
$seen=@{}
$deadline=[DateTime]::UtcNow.AddSeconds(20)
while([DateTime]::UtcNow -lt $deadline) {
    foreach($file in Get-ChildItem -LiteralPath $TraceDirectory -Filter '*.otlp.bin' -ErrorAction SilentlyContinue) {
        $contents=$encoding.GetString([IO.File]::ReadAllBytes($file.FullName))
        if(!$contents.Contains($needle)) { continue }
        foreach($service in @('taskflow-gateway','taskflow-admin','taskflow-scheduler')) {
            if($contents.Contains($service)) { $seen[$service]=$true }
        }
    }
    if($seen.Count -eq 3) {
        [pscustomobject]@{traceId=$TraceId;services=@($seen.Keys);result='PASS'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $TraceDirectory ($TraceId+'.json'))
        Write-Output 'Verified actual OTLP export of the same trace from Gateway, Admin and Scheduler.'
        return
    }
    Start-Sleep -Milliseconds 250
}
throw ('Trace export incomplete; observed services: '+($seen.Keys -join ', '))

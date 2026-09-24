$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$file=Join-Path $root 'deploy/local.env'
foreach($line in Get-Content -LiteralPath $file){
 if($line -match '^WEB_AUTH_SECRET=([a-f0-9]{64})$'){$env:WEB_AUTH_SECRET=$Matches[1]}
}
if(!$env:WEB_AUTH_SECRET){throw 'Run configure-local.ps1 first'}
$health=Invoke-RestMethod 'http://127.0.0.1:8383/health' -TimeoutSec 5
if($health.status -ne 'UP' -or $health.workerEnabled -ne $false){throw 'Diagnosis must be healthy and paused'}
$env:WEB_PORT='3380';$env:WEB_BUSINESS_URL='http://127.0.0.1:8382';$env:WEB_DIAGNOSIS_URL='http://127.0.0.1:8383'
& node (Join-Path $root 'web/server.mjs')

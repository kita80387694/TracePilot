$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$file=Join-Path $root 'deploy/local.env'
if(Test-Path -LiteralPath $file){throw 'Existing local.env retained; do not replace passwords for an existing database volume.'}
& docker.exe volume inspect tracepilot-source_database *> $null
if($LASTEXITCODE -eq 0){throw 'Database volume exists. Restore its original local.env; do not generate different credentials.'}
& docker.exe info --format '{{.ServerVersion}}' *> $null
if($LASTEXITCODE -ne 0){throw 'Start Docker before preparing configuration'}
$lines=foreach($name in @('DB_PASSWORD','OBSERVER_TOKEN','DIAG_API_TOKEN','WEB_AUTH_SECRET')){
 $value=[Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLower()
 "$name=$value"
}
[IO.File]::WriteAllLines($file,$lines)
Write-Output 'Generated private deploy/local.env; ignored by Git. No secrets printed.'

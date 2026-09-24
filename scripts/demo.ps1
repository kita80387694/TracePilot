param([string]$BaseUrl='http://localhost:8080')
$ErrorActionPreference='Stop'
function Login([string]$user) {
    (Invoke-RestMethod "$BaseUrl/api/auth/login" -Method Post -ContentType 'application/json' -Body (@{username=$user;password='Demo-pass-123'} | ConvertTo-Json)).token
}
function Write-Api([string]$path,[string]$token,$body,[string]$key=([guid]::NewGuid().ToString())) {
    $args=@{Uri="$BaseUrl$path";Method='Post';ContentType='application/json';Headers=@{Authorization="Bearer $token";'Idempotency-Key'=$key}}
    if($null -ne $body){$args.Body=$body | ConvertTo-Json}
    Invoke-RestMethod @args
}
$admin=Login 'admin'; $alice=Login 'user1'; $bob=Login 'user2'; $carol=Login 'user3'
$resource=(Write-Api '/api/admin/resources' $admin @{name='M1 demo room';description='repeatable API demo'}).data
$slot=(Write-Api '/api/admin/slots' $admin @{resourceId=$resource.id;startAt=[DateTime]::UtcNow.AddDays(1).ToString('o');endAt=[DateTime]::UtcNow.AddDays(1).AddHours(1).ToString('o');capacity=1}).data
$key=[guid]::NewGuid().ToString()
$reservation=(Write-Api '/api/reservations' $alice @{slotId=$slot.id} $key).data
$replay=(Write-Api '/api/reservations' $alice @{slotId=$slot.id} $key).data
if($reservation.id -ne $replay.id){throw 'Idempotency failed'}
$waiter=(Write-Api '/api/waitlists' $bob @{slotId=$slot.id}).data
$leaver=(Write-Api '/api/waitlists' $carol @{slotId=$slot.id}).data
Write-Api "/api/waitlists/$($leaver.id)/leave" $carol $null | Out-Null
Write-Api "/api/reservations/$($reservation.id)/cancel" $alice $null | Out-Null
Write-Api "/api/reservations/$($reservation.id)/cancel" $alice $null | Out-Null
$promoted=(Invoke-RestMethod "$BaseUrl/api/reservations/$($waiter.id)" -Headers @{Authorization="Bearer $bob"}).data
if($promoted.status -ne 'RESERVED'){throw 'FIFO promotion failed'}
$deadline=[DateTime]::UtcNow.AddSeconds(30)
do {
    $notifications=Invoke-RestMethod "$BaseUrl/api/me/notifications" -Headers @{Authorization="Bearer $bob"}
    $found=@($notifications.data | Where-Object { ($_.message | ConvertFrom-Json).participationId -eq $waiter.id })
    if($found.Count -gt 0){break}
    Start-Sleep -Milliseconds 300
} while([DateTime]::UtcNow -lt $deadline)
if($found.Count -ne 1){throw 'Expected exactly one promotion notification'}
[ordered]@{result='PASS';slotId=$slot.id;reservationId=$reservation.id;fifoPromotedId=$waiter.id;leftId=$leaver.id;promotionNotifications=$found.Count} | ConvertTo-Json

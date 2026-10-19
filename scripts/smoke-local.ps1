param([string]$BaseUrl='http://127.0.0.1:8081',[int]$RedisPort=6379,[int]$RedisDatabase=12,[int]$TimeoutSeconds=900)
$ErrorActionPreference='Stop'
function Assert-City($Condition,$Message){if(!$Condition){throw $Message}}
function Invoke-City($Method,$Path,$Body,$Headers=@{}){
    $args=@{Method=$Method;Uri="$BaseUrl$Path";Headers=$Headers}
    if($null -ne $Body){$args.ContentType='application/json; charset=utf-8';$args.Body=[Text.Encoding]::UTF8.GetBytes(($Body|ConvertTo-Json -Depth 8))}
    Invoke-RestMethod @args
}
function Wait-CityOrder($Id,$Expected,$Headers,$Seconds=12){
    $end=[DateTime]::UtcNow.AddSeconds($Seconds)
    do{
        $result=Invoke-City GET "/voucher-order/$Id" $null $Headers
        if($result.success -and $result.data.status -eq $Expected){return $result.data}
        Start-Sleep -Milliseconds 150
    }while([DateTime]::UtcNow -lt $end)
    throw "Order $Id never reached status $Expected"
}
$phone='139'+(Get-Random -Minimum 10000000 -Maximum 99999999)
$null=Invoke-City POST "/user/code?phone=$phone" $null
$code=(& redis-cli -p $RedisPort -n $RedisDatabase --raw GET "login:code:$phone").Trim()
$login=Invoke-City POST '/user/login' @{phone=$phone;code=$code}
Assert-City $login.success 'Login failed'
$headers=@{authorization=$login.data}
$shop=Invoke-City GET '/shop/1' $null
Assert-City $shop.success 'Cold shop cache must load from database'
$shopAgain=Invoke-City GET '/shop/1' $null
Assert-City $shopAgain.success 'Repeated shop lookup failed'
$originalName=$shop.data.name
try{
    $newName='CitySeek cache update '+[Guid]::NewGuid().ToString('N').Substring(0,6)
    $updated=Invoke-City PUT '/shop' @{id=1;name=$newName}
    Assert-City $updated.success 'Shop update failed'
    $reloaded=Invoke-City GET '/shop/1' $null
    Assert-City ($reloaded.success -and $reloaded.data.name -eq $newName) 'Committed update did not invalidate both cache levels'
}finally{
    $null=Invoke-City PUT '/shop' @{id=1;name=$originalName}
}

$voucher=Invoke-City POST '/voucher/seckill' @{
    shopId=1;title='CitySeek local smoke';subTitle='isolated smoke data';rules='local test';payValue=100;actualValue=200;
    type=1;status=1;stock=1;beginTime=(Get-Date).AddMinutes(-1).ToString('yyyy-MM-ddTHH:mm:ss');endTime=(Get-Date).AddHours(1).ToString('yyyy-MM-ddTHH:mm:ss')
}
Assert-City $voucher.success 'Voucher creation failed'
$voucherId=$voucher.data
$first=Invoke-City POST "/voucher-order/seckill/$voucherId" $null $headers
Assert-City $first.success 'Reservation failed'
$order=Wait-CityOrder $first.data 1 $headers
$duplicate=Invoke-City POST "/voucher-order/seckill/$voucherId" $null $headers
Assert-City (!$duplicate.success) 'Repeated buyer unexpectedly accepted'
Write-Output 'PASS: cold shop query, activity initialization, asynchronous order, repeated-buyer rejection'

if($TimeoutSeconds -gt 30){Write-Output 'Skip expiry smoke: set ORDER_TIMEOUT_SECONDS <=30 and pass -TimeoutSeconds with the same value';exit 0}
$closed=Wait-CityOrder $first.data 4 $headers ($TimeoutSeconds+10)
$released=(& redis-cli -p $RedisPort -n $RedisDatabase --raw GET "seckill:stock:$voucherId").Trim()
Assert-City ($released -eq '1') 'Redis quota not returned after close'
$second=Invoke-City POST "/voucher-order/seckill/$voucherId" $null $headers
Assert-City $second.success 'Same user could not reserve again after cancellation'
$null=Wait-CityOrder $second.data 1 $headers
$paid=Invoke-City POST "/voucher-order/pay/mock/$($second.data)" $null $headers
Assert-City $paid.success 'Local mock payment failed'
$null=Wait-CityOrder $second.data 2 $headers
Start-Sleep -Seconds ($TimeoutSeconds+1)
$null=Wait-CityOrder $second.data 2 $headers
$late=Invoke-City POST "/voucher-order/pay/mock-late/$($first.data)" $null $headers
Assert-City $late.success 'Late payment rehearsal failed'
$null=Wait-CityOrder $first.data 5 $headers
$remaining=(& redis-cli -p $RedisPort -n $RedisDatabase --raw GET "seckill:stock:$voucherId").Trim()
Assert-City ($remaining -eq '0') 'Late payment released the new reservation'
Write-Output 'PASS: Spring Task close, Redis quota return, re-reservation, mock payment, paid-order protection, late-payment task'
Write-Output "Smoke voucher=$voucherId; cancelledOrder=$($first.data); paidOrder=$($second.data)"

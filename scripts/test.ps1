param([string]$RedisServer='redis-server',[string]$RedisCli='redis-cli')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$listener=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
$listener.Start();$port=$listener.LocalEndpoint.Port;$listener.Stop()
$runDir=Join-Path $root ('.local/tests-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$exe=(Get-Command $RedisServer).Source
$proc=Start-Process -FilePath $exe -ArgumentList @('--bind','127.0.0.1','--port',"$port",'--save','""','--appendonly','no','--dir',('"'+$runDir+'"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir 'redis.log') -RedirectStandardError (Join-Path $runDir 'redis-error.log')
try{
    $ready=$false
    for($i=0;$i -lt 30;$i++){
        $pong=& $RedisCli -p $port ping 2>$null
        if($pong -eq 'PONG'){$ready=$true;break}
        if($proc.HasExited){throw 'Isolated Redis exited before startup'}
        Start-Sleep -Milliseconds 100
    }
    if(!$ready){throw 'Isolated Redis did not start'}
    Push-Location $root
    try{& mvn -B "-Dtest.redis.port=$port" test;if($LASTEXITCODE -ne 0){throw 'Tests failed'}}finally{Pop-Location}
}finally{
    if(!$proc.HasExited){& $RedisCli -p $port shutdown nosave 2>$null | Out-Null}
    if(!$proc.WaitForExit(3000)){Stop-Process -Id $proc.Id}
}

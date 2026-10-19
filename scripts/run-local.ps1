param([int]$Port=8081)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Push-Location $root
try{
    & mvn -B -DskipTests package
    if($LASTEXITCODE -ne 0){throw 'Build failed'}
    & java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar --spring.profiles.active=local "--server.port=$Port"
    if($LASTEXITCODE -ne 0){throw 'Application exited with failure'}
}finally{Pop-Location}

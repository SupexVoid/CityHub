param([string]$Mysql='mysql',[string]$Database='cityseek_local',[int]$Port=3306,[string]$User='root')
$ErrorActionPreference='Stop'
if($Database -notmatch '^cityseek_[a-zA-Z0-9_]+$'){throw 'Use a dedicated database named cityseek_...'}
$root=Split-Path $PSScriptRoot -Parent
$originalPwd=$env:MYSQL_PWD
try{
    if($env:MYSQL_PASSWORD){$env:MYSQL_PWD=$env:MYSQL_PASSWORD}
    $args=@('--host=127.0.0.1',"--port=$Port","--user=$User",'--default-character-set=utf8mb4','--connect-timeout=5')
    $exists=& $Mysql @args --batch --skip-column-names "--execute=SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$Database'"
    if($LASTEXITCODE -ne 0){throw 'MySQL connection failed'}
    if($exists){throw 'Database already exists; refusing to overwrite it. Choose a fresh cityseek_... database.'}
    & $Mysql @args "--execute=CREATE DATABASE $Database CHARACTER SET utf8mb4"
    if($LASTEXITCODE -ne 0){throw 'Database creation failed'}
    foreach($file in @('src/main/resources/db/hmdp.sql','src/main/resources/db/local-upgrade.sql')){
        Get-Content -LiteralPath (Join-Path $root $file) -Raw -Encoding UTF8 | & $Mysql @args $Database
        if($LASTEXITCODE -ne 0){throw "Import failed: $file"}
    }
    Write-Output "Initialized dedicated database $Database on port $Port"
}finally{$env:MYSQL_PWD=$originalPwd}

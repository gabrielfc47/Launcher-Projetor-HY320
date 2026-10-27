param(
    [Parameter(Mandatory=$true)]
    [string]$Pacote,
    [Parameter(Mandatory=$true)]
    [string]$Ip,
    [int]$Port = 5555
)

$ErrorActionPreference = "Stop"
$RootDir = $PSScriptRoot
$Adb = "$RootDir\tools\platform-tools\adb.exe"
$Device = "${Ip}:${Port}"
$ApkBackup = "$RootDir\backups\removed-apps\$Pacote.apk"

& $Adb connect $Device

Write-Host "Tentando reativar pacote de sistema $Pacote..." -ForegroundColor Cyan
& $Adb -s $Device shell pm enable $Pacote 2>$null
& $Adb -s $Device shell cmd package install-existing $Pacote 2>$null

if (Test-Path $ApkBackup) {
    Write-Host "Reinstalando APK do backup ($ApkBackup)..." -ForegroundColor Cyan
    & $Adb -s $Device install -r $ApkBackup
}

Write-Host "Concluido!" -ForegroundColor Green

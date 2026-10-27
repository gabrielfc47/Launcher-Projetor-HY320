param(
    [Parameter(Mandatory=$true)]
    [string]$Ip,
    [int]$Port = 5555
)

$ErrorActionPreference = "Stop"
$RootDir = $PSScriptRoot
$ProjectDir = "$RootDir\android-launcher"
$Adb = "$RootDir\tools\platform-tools\adb.exe"
$Device = "${Ip}:${Port}"

Write-Host "=== 1. Compilando e assinando ProjectorCleanHome.apk ===" -ForegroundColor Cyan
& powershell.exe -ExecutionPolicy Bypass -File "$ProjectDir\build.ps1"

Write-Host "=== 2. Conectando ao projetor em $Device ===" -ForegroundColor Cyan
& $Adb connect $Device

Write-Host "=== 3. Instalando no projetor ===" -ForegroundColor Cyan
& $Adb -s $Device install -r --no-incremental "$ProjectDir\ProjectorCleanHome.apk"
& $Adb -s $Device shell cmd package set-home-activity com.gabriel.projectorhome/.MainActivity
& $Adb -s $Device shell settings put secure screensaver_components com.gabriel.projectorhome/com.gabriel.projectorhome.ProjectorDreamService
& $Adb -s $Device shell appops set com.gabriel.projectorhome SYSTEM_ALERT_WINDOW allow
& $Adb -s $Device shell pm disable com.droidlogic/.tvvolume.VolumeService

Write-Host "=== 4. Reiniciando tela inicial ===" -ForegroundColor Cyan
& $Adb -s $Device shell am start -S -n com.gabriel.projectorhome/.MainActivity
Write-Host "Pronto! Alteracoes aplicadas com sucesso no projetor." -ForegroundColor Green

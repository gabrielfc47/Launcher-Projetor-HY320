$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $PSScriptRoot
$ToolsDir = "$RootDir\tools"
$ProjectDir = $PSScriptRoot
$BuildDir = "$ProjectDir\build"

$JavaHome = (Get-ChildItem -Path $ToolsDir -Directory -Filter "jdk*")[0].FullName
$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"

$BuildToolsDir = (Get-ChildItem -Path $ToolsDir -Recurse -Filter "aapt2.exe")[0].DirectoryName
$AndroidJar = (Get-ChildItem -Path $ToolsDir -Recurse -Filter "android.jar")[0].FullName

Remove-Item -Recurse -Force "$BuildDir\gen", "$BuildDir\obj", "$BuildDir\dex", "$BuildDir\out" -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$BuildDir\gen", "$BuildDir\obj", "$BuildDir\dex", "$BuildDir\out" | Out-Null

Write-Host "1. Gerando R.java e APK base com aapt2..."
& "$BuildToolsDir\aapt2.exe" link `
  -o "$BuildDir\out\unaligned.apk" `
  --manifest "$ProjectDir\AndroidManifest.xml" `
  -I "$AndroidJar" `
  --java "$BuildDir\gen"

Write-Host "2. Compilando codigo Java com javac..."
$JavaFiles = Get-ChildItem -Path "$ProjectDir\src", "$BuildDir\gen" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
& "$JavaHome\bin\javac.exe" `
  -source 1.8 -target 1.8 `
  -encoding UTF-8 `
  -bootclasspath "$AndroidJar" `
  -classpath "$AndroidJar" `
  -d "$BuildDir\obj" `
  $JavaFiles

Write-Host "3. Convertendo .class para classes.dex com d8..."
& "$JavaHome\bin\jar.exe" -cf "$BuildDir\classes.jar" -C "$BuildDir\obj" .
& "$BuildToolsDir\d8.bat" `
  --lib "$AndroidJar" `
  --min-api 24 `
  --output "$BuildDir\dex" `
  "$BuildDir\classes.jar"

Write-Host "4. Adicionando classes.dex ao APK..."
Copy-Item "$BuildDir\out\unaligned.apk" "$BuildDir\out\unsigned.apk" -Force
Push-Location "$BuildDir\dex"
& "$JavaHome\bin\jar.exe" -uf "$BuildDir\out\unsigned.apk" classes.dex
Pop-Location

Write-Host "5. Alinhando APK com zipalign..."
& "$BuildToolsDir\zipalign.exe" -f -p 4 "$BuildDir\out\unsigned.apk" "$BuildDir\out\aligned.apk"

Write-Host "6. Gerando keystore de assinatura e assinando APK com apksigner..."
$Keystore = "$BuildDir\debug.keystore"
if (-not (Test-Path $Keystore)) {
  & "$JavaHome\bin\keytool.exe" -genkeypair `
    -keystore $Keystore `
    -storepass android `
    -keypass android `
    -alias androiddebugkey `
    -keyalg RSA `
    -keysize 2048 `
    -validity 10000 `
    -dname "CN=ProjectorHome,O=Android,C=BR"
}

$FinalApk = "$ProjectDir\ProjectorCleanHome.apk"
$PlatPk8 = "$BuildDir\platform.pk8"
$PlatPem = "$BuildDir\platform.x509.pem"
if ((Test-Path $PlatPk8) -and (Test-Path $PlatPem)) {
  & "$BuildToolsDir\apksigner.bat" sign `
    --key $PlatPk8 `
    --cert $PlatPem `
    --out $FinalApk `
    "$BuildDir\out\aligned.apk"
} else {
  & "$BuildToolsDir\apksigner.bat" sign `
    --ks $Keystore `
    --ks-pass pass:android `
    --key-pass pass:android `
    --out $FinalApk `
    "$BuildDir\out\aligned.apk"
}

Write-Host "APK compilado e assinado com sucesso em: $FinalApk"
Get-Item $FinalApk | Select-Object Name, Length

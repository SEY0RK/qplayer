param(
    [string]$Repo = (Get-Location).Path,
    [string]$AppVersion = "1.9.8"
)
$ErrorActionPreference = 'Stop'
$Jdk = $env:JAVA_HOME
if (-not $Jdk) { throw "JAVA_HOME is not set (setup-java required)" }
Push-Location $Repo
$T = Join-Path $Repo "desktop-host/target"
$out = Join-Path $T "QPlayer"
$stage = Join-Path $T "portable-input"

Write-Host "=== 1. Build desktop-host jar ==="
& mvn -B -q -pl desktop-host package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Maven package failed" }
$jar = Get-ChildItem (Join-Path $T "*.jar") | Where-Object { $_.Name -notmatch 'sources|javadoc|original' } | Select-Object -First 1
if (-not $jar) { throw "desktop-host jar not found" }

Write-Host "=== 2. Collect runtime dependencies ==="
& mvn -B -q -pl desktop-host dependency:copy-dependencies "-DoutputDirectory=$T/lib" -DincludeScope=runtime
if ($LASTEXITCODE -ne 0) { throw "dependency copy failed" }
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null
Copy-Item $jar.FullName (Join-Path $stage "qplayer.jar")
Copy-Item (Join-Path $T "lib") (Join-Path $stage "lib") -Recurse

Write-Host "=== 3. jlink runtime (JDK 21, jdk.httpserver included) ==="
$runtime = Join-Path $T "jlink-runtime"
if (Test-Path $runtime) { Remove-Item $runtime -Recurse -Force }
$modules = @("java.base","java.desktop","java.logging","java.management","java.naming",
  "java.sql","java.xml","jdk.unsupported","jdk.crypto.ec","jdk.localedata",
  "jdk.httpserver","jdk.charsets","jdk.dynalink") -join ","
& (Join-Path $Jdk "bin/jlink.exe") --add-modules $modules --include-locales=zh,en --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output $runtime
if ($LASTEXITCODE -ne 0) { throw "jlink failed" }

Write-Host "=== 4. jpackage app-image ==="
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
$icon = Join-Path $Repo "desktop-host/src/main/resources/app-icon.ico"
if (-not (Test-Path $icon)) { throw "missing app-icon.ico" }
& (Join-Path $Jdk "bin/jpackage.exe") --type app-image --name QPlayer --input $stage --main-jar qplayer.jar --main-class dev.t1m3.qplayer.desktop.app.Main --runtime-image $runtime --dest $T --app-version $AppVersion --vendor "TIMER-err (local build)" --icon $icon
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

# Intentionally NO data/ directory: extracting this zip over an existing install
# must never touch the user's playlists, settings or credentials.
if (Test-Path (Join-Path $out "data")) { throw "unexpected data/ in build output" }

Write-Host "=== 5. Inject -Dqplayer.data.dir (no BOM) ==="
$cfg = Join-Path $out "app/qplayer.cfg"
if (-not (Test-Path $cfg)) { $cfg = Join-Path $out "app/QPlayer.cfg" }
if (-not (Test-Path $cfg)) { throw "jpackage cfg not found" }
$lines = Get-Content $cfg | Where-Object { $_ -notmatch 'qplayer.data.dir' }
$final = New-Object System.Collections.Generic.List[string]
$inJavaOptions = $false
foreach ($line in $lines) {
    if ($line -match '^\[(.*)\]') { $inJavaOptions = ($Matches[1] -eq 'JavaOptions') }
    $final.Add($line)
    if ($inJavaOptions -and $line -match '^\[JavaOptions\]') { $final.Add('java-options=-Dqplayer.data.dir=$APPDIR/../data') }
}
# Drop the text-debug switch so the app does not keep writing local log files.
$clean = @($final | Where-Object { $_ -notmatch 'qplayer\.text\.debug' })
[System.IO.File]::WriteAllLines($cfg, $clean, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "=== 6. Compress (no outer folder, no data/) ==="
$zip = Join-Path $T "QPlayer-windows-x64-portable.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path "$out/*" -DestinationPath $zip -Force
Write-Host "done: $zip"
Pop-Location

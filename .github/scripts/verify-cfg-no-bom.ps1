$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipPath = Join-Path (Get-Location) 'desktop-host/target/QPlayer-windows-x64-portable.zip'
if (-not (Test-Path $zipPath)) { throw "missing zip $zipPath" }
$zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
$names = @($zip.Entries | ForEach-Object { $_.FullName })
$zip.Dispose()
$bad = @($names | Where-Object { $_ -match '^data/' })
if ($bad.Count -gt 0) { throw "zip contains data/ entries: $($bad -join ', ')" }
foreach ($need in @('QPlayer.exe','app/QPlayer.cfg','app/qplayer.jar','runtime/')) {
  if (-not ($names -match [regex]::Escape($need))) { throw "zip missing $need" }
}
$cfg = Join-Path (Get-Location) 'desktop-host/target/QPlayer/app/QPlayer.cfg'
if (-not (Test-Path $cfg)) { $cfg = Join-Path (Get-Location) 'desktop-host/target/QPlayer/app/qplayer.cfg' }
$bytes = [System.IO.File]::ReadAllBytes($cfg)
if ($bytes.Length -gt 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) { throw 'cfg has a UTF-8 BOM' }
$text = [System.Text.Encoding]::UTF8.GetString($bytes)
if ($text -notmatch 'qplayer\.data\.dir') { throw 'cfg missing -Dqplayer.data.dir' }
Write-Host "zip OK: $($names.Count) entries, no data/, cfg has no BOM"

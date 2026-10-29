# Builds dist\HomeworkLaunchpad.msix from the Release EXE (run on Windows,
# e.g. GitHub Actions windows-latest). The Store signs it when it's submitted.
param([string]$Exe = "..\bin\Release\net48\HomeworkLaunchpad.exe", [string]$Version = "2.1.0.0")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$id = Get-Content identity.json | ConvertFrom-Json
Remove-Item -Recurse -Force layout, dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory layout\Assets, dist | Out-Null
Copy-Item $Exe layout\HomeworkLaunchpad.exe
Copy-Item Assets\* layout\Assets\
(Get-Content AppxManifest.xml -Raw).Replace("__NAME__", $id.name).Replace("__PUBLISHER__", $id.publisher).Replace("__PUBDISPLAY__", $id.publisherDisplayName).Replace("__VERSION__", $Version) |
  Set-Content layout\AppxManifest.xml -Encoding UTF8
$kit = Get-ChildItem "C:\Program Files (x86)\Windows Kits\10\bin" -Recurse -Filter makeappx.exe | Where-Object { $_.FullName -match "\\x64\\" } | Sort-Object FullName -Descending | Select-Object -First 1
$pri = Join-Path $kit.DirectoryName "makepri.exe"
Push-Location layout
& $pri createconfig /cf priconfig.xml /dq en-GB /pv 10.0.0 /o
if ($LASTEXITCODE -ne 0) { throw "makepri createconfig failed" }
& $pri new /pr . /cf priconfig.xml /of resources.pri /o
if ($LASTEXITCODE -ne 0) { throw "makepri new failed" }
Remove-Item priconfig.xml
Pop-Location
& $kit.FullName pack /d layout /p dist\HomeworkLaunchpad.msix /o
if ($LASTEXITCODE -ne 0) { throw "makeappx failed" }
Get-Item dist\HomeworkLaunchpad.msix

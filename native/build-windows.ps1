# Builds airspy.dll for the host architecture with MSVC and copies it, with the
# libusb DLL, to dist\native\windows-<arch>.
#
# Usage: native\build-windows.ps1 -Arch x64|ARM64 [-Ref <airspy-git-ref>]
#
# Windows uses the prebuilt libusb binaries published by the libusb project.
# pthreads4w and the C runtime are linked statically into airspy.dll by
# native\windows\CMakeLists.txt.
param(
    [Parameter(Mandatory = $true)][ValidateSet('x64', 'ARM64')][string]$Arch,
    [string]$Ref = ''
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true

$Root = Split-Path -Parent $PSScriptRoot
$Versions = @{}
Get-Content "$Root\native\versions.env" | Where-Object { $_ -match '^[A-Z0-9_]+=' } | ForEach-Object {
    $Key, $Value = $_ -split '=', 2
    $Versions[$Key] = $Value
}
if (-not $Ref) { $Ref = $Versions.AIRSPY_REF }
$LibusbVersion = $Versions.LIBUSB_VERSION

if ($Arch -eq 'x64') {
    $Platform = 'windows-x86_64'
    $UsbDir = 'VS2022\MS64\dll'
} else {
    $Platform = 'windows-aarch64'
    $UsbDir = 'VS2025\ARM64\dll'
}
$Out = "$Root\dist\native\$Platform"
$Licenses = "$Root\dist\native\licenses"
$Work = "$Root\build\native-work"
Remove-Item -Recurse -Force $Work, $Out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $Work, $Out, $Licenses | Out-Null
Set-Location $Work

Write-Host "=== libusb $LibusbVersion ==="
$Archive = "libusb-$LibusbVersion.7z"
Invoke-WebRequest "https://github.com/libusb/libusb/releases/download/v$LibusbVersion/$Archive" -OutFile $Archive
$Hash = (Get-FileHash $Archive -Algorithm SHA256).Hash
if ($Hash -ine $Versions.LIBUSB_WINDOWS_7Z_SHA256) {
    throw "libusb archive checksum mismatch: $Hash"
}
7z x $Archive -ousb | Out-Null

Write-Host "=== pthreads4w $($Versions.PTHREADS4W_REF) ==="
git clone --quiet --depth 1 --branch $Versions.PTHREADS4W_REF $Versions.PTHREADS4W_REPOSITORY pthreads4w
$PthreadsCommit = (git -C pthreads4w rev-parse HEAD).Trim()
if ($PthreadsCommit -ne $Versions.PTHREADS4W_COMMIT) {
    throw "pthreads4w $($Versions.PTHREADS4W_REF) is $PthreadsCommit, expected $($Versions.PTHREADS4W_COMMIT)"
}
# context.h misspells the program counter macro in its ARM branch, which makes
# the ARM64 build stop at "#error Module contains CPU-specific code".
$Context = 'pthreads4w\context.h'
(Get-Content -Raw $Context).Replace('#define PTW32_PROGCTR(Context)', '#define __PTW32_PROGCTR(Context)') |
    Set-Content -NoNewline -Encoding ascii $Context

Write-Host "=== libairspy $Ref ==="
# Fetching by ref works for a commit as well as a branch or tag.
git init --quiet airspy
# Keep upstream's LF line endings, which the patches below expect, whatever
# core.autocrlf the machine has.
git -C airspy config core.autocrlf false
git -C airspy fetch --quiet --depth 1 $Versions.AIRSPY_REPOSITORY $Ref
git -C airspy checkout --quiet FETCH_HEAD
$Commit = (git -C airspy rev-parse HEAD).Trim()
$HeaderVersion = (Select-String -Path 'airspy\libairspy\src\airspy.h' -Pattern '^#define AIRSPY_VERSION "(.*)"').Matches[0].Groups[1].Value
if ($Ref -eq $Versions.AIRSPY_REF -and $HeaderVersion -ne $Versions.AIRSPY_VERSION) {
    throw "airspy.h at $Ref is version $HeaderVersion, but AIRSPY_VERSION is $($Versions.AIRSPY_VERSION)"
}
# Fixes to upstream that it has not adopted yet; see native\patches\README.md.
$Patches = @()
foreach ($Patch in Get-ChildItem "$Root\native\patches\*.patch" | Sort-Object Name) {
    Write-Host "Applying $($Patch.Name)"
    git -C airspy apply $Patch.FullName
    $Patches += $Patch.Name
}

cmake -S "$Root\native\windows" -B build -A $Arch `
    '-DCMAKE_POLICY_VERSION_MINIMUM=3.5' `
    '-DCMAKE_POLICY_DEFAULT_CMP0091=NEW' `
    '-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded' `
    "-DAIRSPY_SOURCE_DIR=$Work\airspy" `
    "-DPTHREADS4W_SOURCE_DIR=$Work\pthreads4w" `
    "-DLIBUSB_INCLUDE_DIR=$Work\usb\include" `
    "-DLIBUSB_LIBRARIES=$Work\usb\$UsbDir\libusb-1.0.lib"
cmake --build build --config Release --parallel --target airspy

Write-Host "=== packaging $Platform ==="
$Dll = Get-ChildItem -Recurse -Path build -Filter airspy.dll | Select-Object -First 1
if (-not $Dll) { throw 'airspy.dll was not produced' }
Copy-Item $Dll.FullName "$Out\airspy.dll"
Copy-Item "$Work\usb\$UsbDir\libusb-1.0.dll" "$Out\libusb-1.0.dll"
Copy-Item 'airspy\libairspy\LICENSE.md' "$Licenses\libairspy-LICENSE.md"
Copy-Item 'pthreads4w\LICENSE' "$Licenses\pthreads4w-LICENSE"
@(
    "airspy.ref=$Ref",
    "airspy.commit=$Commit",
    "airspy.version=$HeaderVersion",
    "airspy.patches=$($Patches -join ' ')",
    "libusb.version=$LibusbVersion",
    "pthreads4w.commit=$PthreadsCommit"
) | Set-Content -Encoding ascii "$Out\build-info.properties"
Get-ChildItem $Out

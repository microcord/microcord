param(
    [Parameter(Mandatory)] [string] $ReferenceMain,
    [Parameter(Mandatory)] [string] $Dex2JarHome,
    [string] $AsmJar = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.ow2.asm\asm\9.9\c29635c8a7afa03d74b33c1884df8abb2b3f3dcc\asm-9.9.jar",
    [string] $AsmCommonsJar = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1\org.ow2.asm\asm-commons\9.9\db9165a3bf908ded6b08612d583a15d1d0c7bda0\asm-commons-9.9.jar"
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path "$PSScriptRoot\..\.."
$output = "$root\third_party\webrtc-discord-126.21"
$work = "$env:TEMP\manticord-webrtc-bridge"
$classes = "$ReferenceMain\classes.dex"
$classes3 = "$ReferenceMain\classes3.dex"

$expected = @{
    $classes = "33B7D6B685342AFFBF5A6C6D77D869D00929B0BE05214694DFCC0BD6032F9892"
    $classes3 = "19B34E7F8F67B4CE5CC7C4E101CA5F16F1565E159DA8943F6D5739575049E78C"
}
foreach ($input in $expected.Keys) {
    if ((Get-FileHash -Algorithm SHA256 $input).Hash -ne $expected[$input]) {
        throw "Unexpected input: $input"
    }
}

Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $work, $output | Out-Null
& "$Dex2JarHome\d2j-dex2jar.bat" --force --output "$work\classes3.jar" $classes3
if ($LASTEXITCODE) { throw "dex2jar failed for classes3.dex" }
& "$Dex2JarHome\d2j-dex2jar.bat" --force --output "$work\classes.jar" $classes
if ($LASTEXITCODE) { throw "dex2jar failed for classes.dex" }

$toolClasses = "$work\tool"
New-Item -ItemType Directory -Force $toolClasses | Out-Null
$classpath = "$AsmJar;$AsmCommonsJar"
& javac -encoding UTF-8 -cp $classpath -d $toolClasses "$PSScriptRoot\IsolateWebRtc.java"
if ($LASTEXITCODE) { throw "javac failed" }
& java -cp "$toolClasses;$classpath" IsolateWebRtc "$work\classes3.jar" "$work\classes.jar" "$output\webrtc-discord-126.21.jar"
if ($LASTEXITCODE) { throw "WebRTC isolation failed" }

(& jar tf "$output\webrtc-discord-126.21.jar") |
    Where-Object { $_ -like "org/webrtc/*.class" -and $_ -notlike "org/webrtc/internal/*" } |
    Sort-Object | Set-Content -Encoding utf8 "$output\classes.txt"
$hash = (Get-FileHash -Algorithm SHA256 "$output\webrtc-discord-126.21.jar").Hash
@(
    "$($expected[$classes])  reference/classes.dex"
    "$($expected[$classes3])  reference/classes3.dex"
    "$hash  webrtc-discord-126.21.jar"
) | Set-Content -Encoding ascii "$output\SHA256SUMS"

Write-Output "SHA-256: $hash"

$ErrorActionPreference = "Stop"

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$nativeHashes = [ordered]@{
    "app\src\main\jniLibs\arm64-v8a\libdiscord.so" = "355421FDF37CD6CB8DD09CC94C09A5740CEA875E4D7E84D76DC5A0F0C78D8348"
    "app\src\main\jniLibs\armeabi-v7a\libdiscord.so" = "31D5FCFDCE1017F36A0A05B662651475E9BB4CDC3E67418761CAF5B9EA77D151"
    "app\src\main\jniLibs\x86\libdiscord.so" = "ED34C13A7C2775A4350AFEA9E9C74E06ADA8DF7DD0AB181FF8FC8A4B4EE46B7E"
    "app\src\main\jniLibs\x86_64\libdiscord.so" = "2C9CB350CB866248E0D17501C181ECBE91ED32577E931D6953AAB8960A859B98"
}

foreach ($entry in $nativeHashes.GetEnumerator()) {
    $path = Join-Path $root $entry.Key
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash
    if ($actual -ne $entry.Value) {
        throw "Hash mismatch: $($entry.Key) expected $($entry.Value), got $actual"
    }
}

$aarPath = Join-Path $PSScriptRoot "webrtc-discord-333.5.aar"
$aarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $aarPath).Hash
if ($aarHash -ne "263FFED19CAE511CACD49C666690B2AEC32DAC8C8DC6BCBE9BA31D6EA715DE51") {
    throw "Hash mismatch: webrtc-discord-333.5.aar got $aarHash"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$aar = [IO.Compression.ZipFile]::OpenRead($aarPath)
try {
    $classesEntry = $aar.GetEntry("classes.jar")
    if ($null -eq $classesEntry) { throw "AAR is missing classes.jar" }

    $stream = $classesEntry.Open()
    try {
        $jar = [IO.Compression.ZipArchive]::new($stream)
        try {
            $classes = @($jar.Entries.FullName)
            $requiredClasses = @(
                "org/webrtc/EglBase.class"
                "org/webrtc/JniHelper.class"
                "org/webrtc/PeerConnection.class"
                "org/webrtc/VideoFrame.class"
                "org/webrtc/WebRtcClassLoader.class"
                "org/webrtc/audio/WebRtcAudioManager.class"
                "org/webrtc/audio/WebRtcAudioRecord.class"
                "org/webrtc/audio/WebRtcAudioTrack.class"
            )
            foreach ($class in $requiredClasses) {
                if ($class -notin $classes) { throw "AAR is missing $class" }
            }
            if ($classes -like "com/aliucord/*") {
                throw "AAR unexpectedly contains Aliucord classes"
            }
        } finally {
            $jar.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
} finally {
    $aar.Dispose()
}

"Verified 4 libdiscord.so hashes and WebRTC AAR JNI classes."

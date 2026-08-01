# Discord 333.5 Voice Runtime

Binary-only packaging for Manticord's modern Discord voice runtime. It contains
the public WebRTC Android AAR and no Aliucord APIs, source, or runtime dependency.

## Provenance

- `libdiscord.so`: extracted from Discord tracker build `339011` split APKs.
  The four files are byte-identical in size and ARM64 hash to the binaries in
  [Aliucord PR 752](https://github.com/Aliucord/Aliucord/pull/752) immediately
  before their removal in
  [commit d5a32f2d977866ffe361bec6720a0621b0c527f3](https://github.com/Aliucord/Aliucord/commit/d5a32f2d977866ffe361bec6720a0621b0c527f3).
- WebRTC AAR: built from
  [Aliucord/webrtc commit bb2d68ceab2bc466d6f49ef9f9cbcc7ade982d30](https://github.com/Aliucord/webrtc/tree/bb2d68ceab2bc466d6f49ef9f9cbcc7ade982d30).
  That project identifies its upstream as
  [webrtc-sdk/webrtc commit 13e377b804f68aa9c20ea5e449666ea5248e3286](https://github.com/webrtc-sdk/webrtc/tree/13e377b804f68aa9c20ea5e449666ea5248e3286).
- WebRTC license: BSD 3-Clause, reproduced in `LICENSE`.

The AAR was built from the WebRTC checkout with only this local compatibility
patch:

```diff
-        minSdk = 24
+        minSdk = 21
```

Build command, from the WebRTC checkout:

```powershell
.\gradlew.bat assembleRelease
```

Output: `build/outputs/aar/webrtc-release.aar`, vendored here as
`webrtc-discord-333.5.aar`. API 21 compatibility requires normal Android core
library desugaring for `java.util.Optional`; no source or API shim is included.

## Native Contract

All four ELF files identify Android API 21 and contain WebRTC source stamp
`2024-09-13T04:07:27`.

| ABI | SHA-256 |
| --- | --- |
| `arm64-v8a` | `355421FDF37CD6CB8DD09CC94C09A5740CEA875E4D7E84D76DC5A0F0C78D8348` |
| `armeabi-v7a` | `31D5FCFDCE1017F36A0A05B662651475E9BB4CDC3E67418761CAF5B9EA77D151` |
| `x86` | `ED34C13A7C2775A4350AFEA9E9C74E06ADA8DF7DD0AB181FF8FC8A4B4EE46B7E` |
| `x86_64` | `2C9CB350CB866248E0D17501C181ECBE91ED32577E931D6953AAB8960A859B98` |

The vendored AAR SHA-256 is
`263FFED19CAE511CACD49C666690B2AEC32DAC8C8DC6BCBE9BA31D6EA715DE51`.
Run `./verify.ps1` from this directory to verify every binary and the critical
WebRTC JNI class surface.

No proprietary credentials, signing material, tokens, or official service
configuration are included.

# Third-party code and licences

This project is GPL-3.0 (see `LICENSE`). The third-party code below is vendored directly into the
source tree; copyright stays with the original authors, and the licences and sources are listed here.

## ADB pairing stack
| Source | Path | Version | Licence |
| --- | --- | --- | --- |
| [MuntashirAkon/libadb-android](https://github.com/MuntashirAkon/libadb-android) | `app/src/main/java/com/hexo141/liquidglass/adb/**` | 3.1.1 | `GPL-3.0-or-later OR Apache-2.0` (used here under Apache-2.0) |
| [MuntashirAkon/spake2-java](https://github.com/MuntashirAkon/spake2-java) (the plain-Java `java` module) | `app/src/main/java/com/hexo141/liquidglass/crypto/**` | 2.2.0 | LGPL-3.0 |
| [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) | Reference for the pairing flow and the notification interaction in `AdbPairingService` and `AdbPairDialogFragment` | master | Apache-2.0 |

Files that were vendored:

- `com/hexo141/liquidglass/adb/`: `PairingConnectionCtx`, `PairingAuthCtx`, `KeyPair`, `SslUtils`,
  `AndroidPubkey`, `StringCompat`, `ByteArrayNoThrowOutputStream`, `AdbMdns` (originally in the
  `…adb.android` package, moved to `…adb.mdns`), `AndroidUtils`, plus `AdbConnection`, `AdbProtocol`,
  `AdbStream`, `LocalServices`, `PRNGFixes` and the exception types in the same directory, which this
  feature does not use directly but which the connection code needs.
- `com/hexo141/liquidglass/crypto/`: the plain-Java SPAKE2 implementation
  (`spake2/Spake2Context`, `Spake2Role`) and the Ed25519 / X25519 arithmetic it depends on
  (`ed25519/*`, `x25519/*`).

Local changes: only the package name was rewritten from `io.github.muntashirakon.{adb,crypto}` to
`com.hexo141.liquidglass.{adb,crypto}`; the socket-side Java implementation is untouched. No native
code is involved, so no NDK is required for it.

## vivo/iQOO USB-OTG flow

| Source | Path | Licence |
| --- | --- | --- |
| `yuehong-privilege-assistant` (user-provided source archive) | `app/src/main/java/com/hexo141/liquidglass/adb/UsbAdbClient.kt`, `AdbMessage.kt`, `app/src/main/assets/vivo_ksu_activate.sh` | MPL-2.0 |
| [KSuRoot](https://github.com/hmascs/KSuRoot/) and the per-device ports listed in `app/src/main/assets/vivo_payloads.json` | `app/src/main/jniLibs/arm64-v8a/libksu_vivo_*.so` and the other payloads named in that catalog | Apache-2.0 / per-payload source |

The USB host ADB transport, the wired escalation sequence and the KernelSU activation script are
ported from the user-provided `yuehong-privilege-assistant` project (MPL-2.0). Local changes: the
package was rewritten, the reference `AdbKey` was replaced with an adapter over this project's
existing `AdbKeyManager` / `AndroidPubkey`, the staging file names use `openroot`, and the UI is
new. The MPL-2.0 and Apache-2.0 texts are kept in `app/src/main/assets/licenses/`.

The payload binaries are redistributed as prebuilt artifacts, not rebuilt from source here. Each
entry in `vivo_payloads.json` carries its original source identifier and SHA-256 digest; the
catalog verifies the digest before a payload is pushed. See the original project's `NOTICE.md` and
the linked upstream repositories for authorship. Only use this flow on hardware you own or are
authorised to test.

## Liquid Glass button

| Source | How it is used | Version | Licence |
| --- | --- | --- | --- |
| [Kyant0/Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) (`io.github.kyant0:backdrop`) | Maven dependency (the library itself) | 2.0.0-alpha03 | Apache-2.0 |
| The sample in the same repository, `components/LiquidButton.kt` | Reference for `app/src/main/java/com/hexo141/liquidglass/ui/LiquidGlassButton.kt`, a capsule glass button with the drag highlight removed | master | Apache-2.0 |

The library is a Compose implementation, which is why `MainActivity` hosts the screen in a
`ComposeView`, and why the Compose compiler plugin version matches the Kotlin built into AGP 9.3
(`org.jetbrains.kotlin.plugin.compose:2.2.10`). The dependency also pulls the JetBrains Compose
multiplatform artifacts (the Android variant maps to `androidx.compose.*` 1.10.x) and
`io.github.kyant0:shapes`, all from Maven Central (Aliyun mirror).

## CVE-2025-21479 payload

| Source | Path | Notes | Licence |
| --- | --- | --- | --- |
| [zhuowei/cheese](https://github.com/zhuowei/cheese) | `native-gpu/quest/**` | Quest 3/3S build, commit `1ae1e77` | no licence declared upstream |
| [Qingizi7/cve-2025-21479_iqooneo8](https://github.com/Qingizi7/cve-2025-21479_iqooneo8) | `native-gpu/snapdragon/**` | iQOO Neo8 / SM8475 port, commit `b63479` | no licence declared upstream |

Both upstream repositories state in their README that they are for security research on devices you
own or are authorised to test. `adrenaline.h` comes from hawkes' Project Zero Adrenaline and
`kallsyms_lookup.c` from the cheese lineage above. The local changes are limited to splitting the
directories, the NDK Makefile and packaging into the APK; the vulnerability code is upstream as-is.

## CVE-2026-64560 payload

| Source | Path | Notes | Licence |
| --- | --- | --- | --- |
| [Wangs-official/opace6-cve-2026-64560](https://github.com/Wangs-official/opace6-cve-2026-64560) | `native-pct/opace6/**` | OnePlus Ace 6 port (OP6113L1 / 6.6.118 GKI), commit `4558a33` | Apache-2.0 |

That port descends from the `NebuSec/CyberMeowfia` Android-CVE-2026-64560 research (originally the
Xiaomi 15 `dada` build), and its `NOTICE` is kept next to the source in `native-pct/opace6/NOTICE`.
The vulnerability code is unchanged; there are exactly two local changes:

- the root child honours an `OPENROOT_HANDOFF` environment variable and `exec`s the staged `loader`
  (`native-or/bootstrap.c`) instead of sitting in an interactive shell nobody types into;
- the directory split and the NDK Makefile (`native-pct/Makefile`).

## Bundled wallpaper

`app/src/main/res/drawable-nodpi/bg.jpg` was provided by the user of this project (from a local
download, 2160×3840, resampled to 1080×1920 on the way in). Its copyright and licence are the
provider's responsibility; this project does not relicense it.

## Referenced but not copied

`RikkaApps/Shizuku` implements SPAKE2 in `manager/src/main/jni/adb_pairing.cpp` on top of BoringSSL
(`libadb.so`). This project uses the plain-Java SPAKE2 above instead: same behaviour, no native
library to build.

## Added runtime dependencies (all from Maven Central)

- `org.bouncycastle:bcprov-jdk18on` — HKDF / AES-GCM after SPAKE2
- `org.bouncycastle:bcpkix-jdk18on` — self-signed certificate for the TLS client
- `org.conscrypt:conscrypt-android` — TLSv1.3 `exportKeyingMaterial`
- `androidx.annotation:annotation`
- `io.github.kyant0:backdrop` — liquid glass button (see above)

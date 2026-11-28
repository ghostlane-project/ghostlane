# Building from source

What each platform runs, what a build needs installed, the commands and where their outputs land. [Back to the README](../README.MD).

Everything below is for building the app yourself; the [downloads](../README.MD#download) need none of it.

## Platform Matrix

| Platform and modes | What carries the packets | Engine |
| --- | --- | --- |
| Android: TUN, proxy | `VpnService` + `hev-socks5-tunnel` | `ghostlane-core`, one Rust library in the app: its core runs every kind of line, its olcRTC client the rooms |
| iOS: System VPN | `hev-socks5-tunnel` in front of one engine | `ghostlane-core`, one Rust library inside the extension |
| macOS: TUN, proxy | root daemon via `SMAppService` running hev behind the engine; OS proxy settings and a PAC otherwise | `ghostlane-engine`, a child of the app |
| Windows: TUN, proxy | hev + Wintun, the line leaving by the interface the machine leaves by; the tunnel answers every name itself and the firewall refuses port 53 beside it (the guard the old sing-box tun called `strict_route`); OS proxy settings otherwise | `ghostlane-engine`, a child of the app |
| Linux: TUN | `hev-socks5-tunnel` + policy routes; an optional kill switch on a dummy device | `ghostlane-engine`, a child of the app |
| Linux CLI | the same hev and policy routes, or a local proxy | `ghostlane-engine`, a child of the CLI |

No package carries a Go program; `scripts/check-no-go.sh` (K1) reads every
package for one. The Rust core is built at the commit in
`scripts/ghostlane-core-pin.sh`; [rust-core.md](rust-core.md) says how.

iOS is the odd one because an `NEPacketTunnelProvider` gets about 50 MB: the
extension links no second engine, `hev-socks5-tunnel` owns the tun on every line,
and `ghostlane-core` runs behind it. The measurements that led there are in
[ios-one-go-runtime.md](ios-one-go-runtime.md).

## Requirements

| Area | Requirement |
| --- | --- |
| JVM | JDK 21, what CI builds with; the Android source and target level is 17 |
| Android | Android Studio, SDK 37, NDK 28.2.13676358 |
| Desktop native assets | a C toolchain for `hev-socks5-tunnel` (and, for the Mac, the tunnel daemon's bridge); `ghostlane-engine` is built by the Core build and put in the package, or built with `scripts/build-ghostlane-core-host.sh` |
| iOS | macOS and Xcode; the first build fetches `Cores.xcframework` and `HevSocks5Tunnel.xcframework` from this repository's release tags, and builds `ghostlane-core` with `scripts/build-ghostlane-core-ios.sh`, which needs `rustup` and `git` |
| Linux TUN | `iproute2`, `/dev/net/tun`, root/Polkit/sudo privileges; Linux 5.7 or later for any server other than an olcRTC room (the core is bound to the physical interface, which older kernels allow only to root). The kill switch needs the kernel's `dummy` device; without it the tunnel comes up without the switch, and the log says so |
| Windows TUN | UAC elevation when TUN starts and bundled Wintun runtime |

## Commands

| Task | Command |
| --- | --- |
| Android debug APK | `./gradlew :androidApp:assembleDebug` |
| Install Android debug APK | `./gradlew :androidApp:installGithubDebug` |
| iOS simulator app | `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug build` |
| Run desktop app | `./gradlew :desktopApp:run` |
| Run desktop app with hot reload | `./gradlew :desktopApp:hotRun --auto` |
| Build desktop native assets | `./gradlew :desktopApp:buildDesktopNativeAssets` |
| Package current host OS | `./gradlew :desktopApp:packageReleaseDistributionForCurrentOS` |
| Main checks | `./gradlew :sharedUI:jvmTest :desktopApp:compileKotlin :androidApp:assembleDebug` |
| JVM tests | `./gradlew :sharedUI:jvmTest` |
| Run the emitted configs past sing-box itself | `scripts/check-singbox-configs.sh` |
| Stop and restart the Android tun's native code in one process (Linux) | `scripts/test-android-hev-restart.sh` |

The main checks are what PR checks run, in that order; `check-singbox-configs.sh`
follows them there, and
`test-android-hev-restart.sh` runs after the main checks when the Android tun's
native code changed.

## Build Outputs

| Target | Command | Output |
| --- | --- | --- |
| Android debug | `./gradlew :androidApp:assembleDebug` | `androidApp/build/outputs/apk/github/debug/androidApp-github-debug.apk` |
| Android release | `./gradlew :androidApp:assembleGithubRelease` | `androidApp/build/outputs/apk/github/release/androidApp-github-release.apk` |
| Android App Bundle for Play | `./gradlew :androidApp:bundlePlayRelease` | `androidApp/build/outputs/bundle/playRelease/androidApp-play-release.aab` |
| iOS simulator | `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug build` | Xcode DerivedData app product |
| macOS DMG | `./gradlew :desktopApp:packageReleaseDmg` | `desktopApp/build/compose/binaries/main-release/dmg/Ghostlane-<version>.dmg` |
| Windows EXE | `.\gradlew.bat :desktopApp:packageReleaseExe` | `desktopApp\build\compose\binaries\main-release\exe\Ghostlane-<version>.exe` |
| Windows MSI | `.\gradlew.bat :desktopApp:packageReleaseMsi` | `desktopApp\build\compose\binaries\main-release\msi\Ghostlane-<version>.msi` |
| Windows portable zip | `.\gradlew.bat :desktopApp:packageReleasePortableZip` | `desktopApp\build\compose\binaries\main-release\portable\Ghostlane-<version>-windows-amd64-portable.zip` |
| Linux AppImage | `./gradlew :desktopApp:packageReleaseLinuxAppImage` | `desktopApp/build/compose/binaries/main-release/appimage/Ghostlane-<version>-amd64.AppImage` |

## Native Assets

Built into `desktopApp/build/generated/desktopNativeResources/` by
`buildDesktopNativeAssets`, which verifies the set the host actually needs:

| Asset | Path |
| --- | --- |
| macOS tunnel-daemon bridge | `native/libolcboxtunneld.dylib` |
| Windows `hev` and Wintun runtime | `native/hev.exe`, `native/wintun.dll` |
| Linux `hev-socks5-tunnel` | `native/hev-socks5-tunnel-linux-<arch>` |
| `ghostlane-engine` | `native/ghostlane-engine`, `native/ghostlane-engine.exe` on Windows (put in by the package build from the Core build) |

`<arch>` is `arm64` or `amd64`. The macOS bridge is why a missing asset is worth
a failed build rather than a warning: absent, the settings row for the system-wide
tunnel simply does not appear, and nothing anywhere says why.

## Notes

- Android native ABIs: `armeabi-v7a`, `arm64-v8a`, `x86_64`, picked with
  `-Polcbox.android.abiFilters`. The release builds a universal APK and a
  separate `armeabi-v7a` one.
- The `github` flavor carries the in-app updater, which looks at this
  repository's releases; the `play` flavor drops it along with the two
  permissions Play refuses.
- Android release builds require `keystore.properties` in the repository root.
- `ghostlane-engine` is not produced by `buildDesktopNativeAssets` either: the
  release workflow builds it at the pin and puts it into the package. A locally
  packaged build has no engine until one is put there, and then connects nothing.
- The release workflow signs and notarises every macOS build, published or not:
  `SMAppService` refuses to register the tunnel daemon from an un-notarised
  bundle. A local `packageReleaseDmg` is neither unless `MACOS_SIGN_IDENTITY`
  and the notary credentials are set.
- Windows installers must be built on Windows with `jpackage` and WiX available.
- Linux TUN interface: `olcbox0`; the kill switch's dummy device: `olcboxks0`.
- Linux stop removes policy routes and reverts `systemd-resolved` settings when
  `resolvectl` is available. With the kill switch on, that is so for a running
  tunnel you disconnect. When the tunnel's process stops by itself they stay in
  place, which is the block, and until you are connected again only turning
  the switch off removes them.

`keystore.properties`:

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

Linux privilege override:

```bash
OLCBOX_LINUX_PRIVILEGE=sudo ./gradlew :desktopApp:run
OLCBOX_LINUX_PRIVILEGE=pkexec ./gradlew :desktopApp:run
```

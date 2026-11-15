# Moka VPN — Design

Status: local preparation (2026-11-14), followed the same day by the
repo/CI phase Moriya approved: public GitHub repo `ysk-agent/moka-vpn`,
pinned signing from the first CI build, and the Keystore-backed
encrypted-prefs implementation (§8). Later the same day Moriya
redirected: CI optimisation is cancelled and a working VPN comes
first — the Layer 3 backend is now wired for real (§3 contract
implemented in the adapter package, including network-change/DNS
callbacks in the service), and the current build route is the local
manual pipeline (`build-manual.sh`: aapt2 → kotlinc → d8 → zipalign →
apksigner with the pinned key, AAR taken from the per-ref release
cache in `app/libs/`). The CI workflow remains as the fallback route.
Drive publication is not part of any step so far. Route A decided by
Moriya on 2026-11-14: a standalone Android VPN app embedding
libtailscale, first installed and evaluated on moto (moto g05,
Android 15).

Version notes: v0.1.1 (2026-11-14) fixed auth-key enrolment by
mirroring upstream's login chain and enrolled moto as node moto-g05.
v0.1.2 added the "Use Tailscale DNS" (CorpDNS) toggle that fixes
Android Private DNS breakage, plus status-display fixes. v0.1.3 added
the host-side AdGuard DNS mode and the in-app version display.
**v1.0.0 (2026-11-15)** adds the extension architecture (§9) with the
Health and Startup management extensions — the helper that was this
app's founding purpose — and the state-divergence fix (§9.5).
**v1.1.0 (2026-11-15)** adds the Proxy extension (§9.6), the third
built-in extension, and current-status delivery when an extension is
started. **v1.2.0 (2026-11-16)** adds the ADB keepalive extension
(§9.7), the fourth built-in, which guards the wireless-debugging
toggle the node phones' adb access depends on. **v1.3.0
(2026-11-16)** extends that extension with a remote debug control
endpoint (§9.7): an allowlist- and token-guarded listener on the
node's tailnet address through which the VM can turn USB
debugging on and off — the recovery path for mobile data, where
wireless debugging cannot keep adbd alive. The
major bump is deliberate: the app is no longer only a VPN client, it
is a host for node-stack features.

- Package: `dev.moriya.vpn`
- App name: Moka VPN
- Fixed APK file name at handover: `mokavpn.apk`
- Upstream source inspected: `~/workspace/tailscale-android-src/`
  (shallow clone of `github.com/tailscale/tailscale-android`;
  `go.mod` requires Go 1.27.3, `tailscale.com v1.105.0-pre`)
- Template project: `~/workspace/apps-updater/` (Gradle structure,
  pinned-signing pattern, GitHub Actions build, emulator launch test)

## 1. Goal

Replace the unstable shell of the stock Tailscale app — not its stable
core — with a thin app of our own:

- The Go core (`libtailscale`, the same code base the CLI builds on) does
  coordination, WireGuard, NAT traversal, DERP relaying and MagicDNS.
- Our code owns only the Android side: the `VpnService`, the foreground
  notification, enrolment with an auth key, connect/disconnect, and a
  minimal status display.
- The app must be designatable as Android's always-on VPN, so the system
  restarts it if it is killed — the failure mode seen on the Libero node
  on 2026-11-13, where the stock Tailscale app died together with the
  phone-node stack and did not come back by itself.
- Android allows exactly one active VPN per profile, so on a test device
  this app and the stock Tailscale app are mutually exclusive.

Non-goals for v0.1 are listed under Scope (§5).

## 2. Architecture — three layers, separable by construction

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1 — UI + Android shell            (package dev.moriya.vpn,
│   MainActivity, MokaVpnService,          .service subpackage)
│   MokaVpnApp)
│   Knows Android and our own types only. Never imports
│   libtailscale. MokaVpnService implements our own TunHost
│   interface, not libtailscale.IPNService.
├─────────────────────────────────────────────────────────────┤
│ Layer 2 — Tunnel contract               (package .tunnel)
│   TunnelBackend interface + our own types: TunnelConfig,
│   TunnelState, TunnelStatus, TunHost, TunBuilder, TunSession.
│   No Android types, no libtailscale types, no JSON of any
│   backend's wire format.
├─────────────────────────────────────────────────────────────┤
│ Layer 3 — libtailscale adapter          (package
│   LibtailscaleBackend + bridges          .backend.libtailscale)
│   The ONLY code that imports libtailscale.* or mentions its
│   types, JSON (ipn.Notify, LocalAPI payloads) or statics.
│   Bridges implement libtailscale.AppContext / IPNService /
│   VPNServiceBuilder / NotificationCallback on the adapter side
│   of the boundary, wrapping Layer 1's TunHost.
└─────────────────────────────────────────────────────────────┘
```

The composition root is `MokaVpnApp`: it constructs the one
`TunnelBackend` implementation and exposes it typed as `TunnelBackend`.
Layer 1 code that needs the backend sees only the interface. Referencing
the adapter *class* at the composition root is not a libtailscale
dependency; importing a libtailscale *type* anywhere outside Layer 3 is
a rule violation, checkable with one grep:

```
grep -rn "^import libtailscale" app/src/main/java --include='*.kt' \
  | grep -v 'backend/libtailscale'
# must print nothing (imports only; prose mentions in comments are fine,
# and the draft CI workflow runs exactly this check)
```

### 2.1 What "separable later" means — and what it does not mean

Moriya's question was whether the app can later be cut away from
libtailscale. The answer this architecture gives is **backend
replacement**:

- A future backend — a plain WireGuard backend (wireguard-android /
  wireguard-go), a `tsnet`-based backend, a Headscale-oriented fork of
  libtailscale, or a backend of our own — is a new Layer 3 module
  implementing `TunnelBackend`, plus one changed line at the composition
  root. Layers 1 and 2 do not change: the UI, the `VpnService` shell,
  the always-on handling, the notification, and the enrolment flow are
  all backend-independent.
- State that must survive a backend swap is deliberately kept in our
  own terms in Layer 2 (state, own addresses, peer count, last error).
  Anything libtailscale-specific (its state store, its prefs, its
  `ipn.Notify` JSON) stays inside Layer 3, so a replacement backend is
  not required to emulate Tailscale's internal formats. The realistic
  cost of a swap is re-enrolment (a node key lives in the old backend's
  store), not a rewrite.

The alternative reading — **carving pieces out of libtailscale itself**
(e.g. keeping its magicsock but replacing its control client) — is
explicitly *not* the planned path. libtailscale is a monolith produced
by `gomobile bind` over the whole `tailscale.com` module; its internal
packages are not a stable or separable API, and the only supported
surface is the one in `interfaces.go` (§3). Forking and maintaining a
carved subset would mean tracking upstream's internals indefinitely.
If a future need points that way, it is a new project decision, not an
extension of this design.

A second, weaker form of separability is kept for free: Layer 3 can be
disabled entirely (the stub shipped in this skeleton), in which case the
app still builds, launches, and reports "backend not integrated" —
useful for CI and for UI work before the AAR exists.

## 3. The libtailscale host contract (from the actual source)

All references are to `~/workspace/tailscale-android-src/libtailscale/`
unless a Kotlin path is given. The generated AAR exposes these to Kotlin
as package `libtailscale` (`Libtailscale` statics, interfaces mirroring
the Go names; Go `int` surfaces as Kotlin `Long` in several places, e.g.
`App.getSDKInt()` in upstream `App.kt`).

### 3.1 Entry point — `interfaces.go`: `Start(...)`

```go
func Start(dataDir, directFileRoot string, hwAttestationPref bool,
           appCtx AppContext) Application
```

Upstream call site: `App.startLibtailscale()` in
`android/src/main/java/com/tailscale/ipn/App.kt` passes
`filesDir.absolutePath` as both arguments (directFileRoot is a SAF URI
string instead when Taildrop storage is configured), an MDM-driven
hardware-attestation preference, and the `App` itself as `AppContext`.
Exactly one `Application` per process.

### 3.2 Host → Go: `AppContext` — `interfaces.go` (27 methods)

Implemented upstream by `com.tailscale.ipn.App` (`App.kt`). Our
implementation lives in Layer 3 (`LibtailscaleAppContext`). Methods:

| Method | Notes / upstream implementation |
|---|---|
| `Log(tag, logLine string)` | → `android.util.Log` |
| `EncryptToPref(key, value string) error` | upstream: EncryptedSharedPreferences (Jetpack Security). Ours: `EncryptedPrefStore` — Keystore AES-GCM over plain prefs, Layer 3 only (decided 2026-11-14, §8) |
| `DecryptFromPref(key string) (string, error)` | same store, `""` if unset |
| `GetStateStoreKeysJSON() string` | keys of that store with `statestore-` prefix stripped, as a JSON array |
| `GetOSVersion() (string, error)` | `Build.VERSION.RELEASE` |
| `GetSDKInt() (int, error)` | `Build.VERSION.SDK_INT` (Kotlin override returns `Long`) |
| `GetDeviceName() (string, error)` | `Settings.Global.DEVICE_NAME`, else MANUFACTURER + MODEL |
| `GetInstallSource() string` | upstream: `AppSourceChecker` |
| `ShouldUseGoogleDNSFallback() bool` | upstream: build constant |
| `IsChromeOS() (bool, error)` | `hasSystemFeature("android.hardware.type.pc")` |
| `IsClientLoggingEnabled() (bool, error)` | user/MDM preference |
| `GetInterfacesAsJson() (string, error)` | JSON of all `NetworkInterface`s: name, index, mtu, up/broadcast/loopback/pointToPoint/multicast, addrs `[{ip, prefixLen}]` (upstream `InterfaceJson`/`AddrJson` in `App.kt`) |
| `GetPlatformDNSConfig() string` | upstream: `DnsConfig.dnsConfigAsString`, fed by ConnectivityManager callbacks |
| `GetSyspolicyStringValue(key string) (string, error)` | MDM/syspolicy; must *throw* (upstream: `NoSuchKeyException`) when unset — error is the "not configured" signal |
| `GetSyspolicyBooleanValue(key string) (bool, error)` | same, `"true"` comparison upstream |
| `GetSyspolicyStringArrayJSONValue(key string) (string, error)` | same, JSON array string |
| `HardwareAttestationKeySupported() bool` | StrongBox KeyStore feature, API 28+ |
| `HardwareAttestationKeyCreate() (string, error)` | Android KeyStore (upstream `util/HardwareKeyStore`) |
| `HardwareAttestationKeyRelease(id string) error` | |
| `HardwareAttestationKeyPublic(id string) ([]byte, error)` | |
| `HardwareAttestationKeySign(id string, data []byte) ([]byte, error)` | |
| `HardwareAttestationKeyLoad(id string) error` | |
| `BindSocketToNetwork(fd int32) bool` | bind fd to cached default `Network` (upstream `NetworkChangeCallback.cachedDefaultNetwork`) |
| `GetUserCACertsPEM() ([]byte, error)` | user CAs from `AndroidCAStore`, PEM-encoded |

v0.1 may implement the syspolicy trio as "always throw not-configured"
and hardware attestation as "unsupported" (`Start` is then called with
`hwAttestationPref = false`); both are honest degradations the Go side
already handles (see `tailscale.go`: attestation is only registered when
supported *and* preferred).

### 3.3 Host → Go: `IPNService` — `interfaces.go` (6 methods)

Implemented upstream by `IPNService.kt` — a `VpnService` subclass that
itself implements the Go interface. In our architecture the Layer 1
service does **not** implement it; Layer 3 wraps our `TunHost` in a
bridge object that does (`LibtailscaleIPNService`).

| Method | Meaning |
|---|---|
| `ID() string` | unique per service instance (upstream: random UUID per instance) |
| `Protect(fd int32) bool` | `VpnService.protect(fd)` — keep backend sockets out of the tunnel |
| `NewBuilder() VPNServiceBuilder` | a fresh builder for one TUN establishment (see §3.4) |
| `Close()` | teardown: notify disconnect (`ServiceDisconnect`), stop self |
| `DisconnectVPN()` | `stopSelf()` upstream |
| `UpdateVpnStatus(bool)` | publish VPN-active state to the UI layer |

### 3.4 Host → Go: `VPNServiceBuilder` + `ParcelFileDescriptor` — `interfaces.go`

Upstream: `VPNServiceBuilder.kt`, wrapping `VpnService.Builder`
(pre-configured by `IPNService.newBuilder()`: configure intent, AF_INET
+ AF_INET6 families, `setMetered(false)` on API 29+, underlying
networks, MDM/user allow/disallow package lists).

| Method | Android equivalent |
|---|---|
| `SetMTU(int32) error` | `Builder.setMtu` (Go default MTU 1280, `tailscale.go`) |
| `AddDNSServer(string) error` | `Builder.addDnsServer` |
| `AddSearchDomain(string) error` | `Builder.addSearchDomain` |
| `AddRoute(string, int32) error` | `Builder.addRoute(addr, prefix)` |
| `ExcludeRoute(string, int32) error` | `Builder.excludeRoute(IpPrefix)` — API 33+ only upstream; no-op below |
| `AddAddress(string, int32) error` | `Builder.addAddress(addr, prefix)` |
| `Establish() (ParcelFileDescriptor, error)` | `Builder.establish()`; result wraps `android.os.ParcelFileDescriptor` with `Detach() (int32, error)` → `detachFd()` |

Note the builders are single-use and the Go side may call
`NewBuilder()` repeatedly (each `updateTUN` in `backend.go` builds a new
TUN from the current `router.Config` + `dns.OSConfig` pair delivered via
`VPNFacade.SetBoth` — `vpnfacade.go`). The Kotlin side never sees those
Go config types; they are collapsed into the builder calls above.

### 3.5 Go → host: global callbacks — `interfaces.go`, `callbacks.go`

| Function | Direction / use |
|---|---|
| `Libtailscale.requestVPN(service)` (`RequestVPN(IPNService)`) | Kotlin → Go: hand a started `IPNService` to the backend. Upstream calls it from `IPNService.onStartCommand` for `ACTION_START_VPN`, for the `"android.net.VpnService"` (always-on) start, and on restart-after-kill. Go side (`backend.go`): sets the `netns` protect/bind functions from this service, rebinds magicsock, then applies any pending config via `updateTUN` |
| `Libtailscale.serviceDisconnect(service)` (`ServiceDisconnect(IPNService)`) | Kotlin → Go: service is going away (upstream `IPNService.close()`) |
| `Libtailscale.onDNSConfigChanged(ifname)` (`OnDNSConfigChanged(string)`) | Kotlin → Go: default network's interface changed (upstream `NetworkChangeCallback.onLinkPropertiesChanged`) |
| `Libtailscale.onGatewayChanged(gatewayIP)` (`OnGatewayChanged(string)`) | Kotlin → Go: default gateway changed (`netmon.UpdateLastKnownDefaultGateway`) |
| `Libtailscale.sendLog(bytes)` (`SendLog([]byte)`) | Kotlin → Go logging channel (buffered, drops when full) |
| `Libtailscale.setShareFileHelper(helper)` (`SetShareFileHelper(ShareFileHelper)`) | Taildrop only — **out of v0.1 scope**; do not call |

### 3.6 Go → host: `Application` — `interfaces.go`, `localapi.go`, `notifier.go`

Returned by `Start`. Our adapter uses exactly these members:

| Method | Use in v0.1 |
|---|---|
| `CallLocalAPI(timeoutMillis int, method, endpoint string, body InputStream) (LocalAPIResponse, error)` | all control operations, as HTTP over an in-process handler (`/localapi/v0/<endpoint>`). `LocalAPIResponse`: `StatusCode() int`, `BodyBytes() ([]byte, error)`, `BodyInputStream() InputStream`. Body is supplied via an `InputStream` adapter (`Read() ([]byte, error)`, `Close() error`; upstream `ui/util/InputStreamAdapter.kt`) |
| `WatchNotifications(mask int, cb NotificationCallback) NotificationManager` | state stream. `cb.OnNotify(notifyJSON []byte) error` receives a marshalled `ipn.Notify` per change; returned manager's `Stop()` unsubscribes. Upstream mask (`Notifier.kt`): `Prefs(4) \| InitialState(2) \| PeerChanges(4096) \| NoNetmap(8192) \| InitialStatus(16384) \| InitialHealthState(128)`. Fields we consume: `State`, `Prefs`, `NetMap`/`SelfChange` (own addresses), `LoginFinished`, `BrowseToURL` (not used in auth-key flow), `Health` |
| `NotifyPolicyChanged()` | only with MDM/syspolicy — not in v0.1 |
| `SetClientLoggingEnabled(bool)` | mirror the `IsClientLoggingEnabled` preference |
| `CallLocalAPIMultipart(...)` | Taildrop/file APIs — not in v0.1 |

LocalAPI endpoints used by v0.1 (upstream `ui/localapi/Client.kt`,
paths relative to `/localapi/v0/`):

| Endpoint | Method | Purpose |
|---|---|---|
| `start` | POST | body `ipn.Options{ AuthKey, UpdatePrefs }` (`ui/model/Ipn.kt`: `Options.AuthKey`) — the auth-key enrolment + start path |
| `prefs` | GET / PATCH | read prefs; PATCH `MaskedPrefs{ WantRunning }` is how upstream connect/disconnect toggles the backend (`App.setWantRunning`) |
| `status` | GET | `ipnstate.Status`: self node, Tailscale IPs, peers — source for the status display if Notify parsing is insufficient |
| `logout` | POST | clear node key / enrolment (adapter-level "reset") |
| `reset-auth` | POST | reset authentication state (re-enrolment recovery) |
| `login-interactive` | POST | **not used in v0.1** (browser login is out of scope) |

### 3.7 What upstream additionally wires (and v0.1 must, or consciously skips)

- **Network change monitoring** (`NetworkChangeCallback.kt`):
  ConnectivityManager callbacks track the default network, call
  `VpnService.setUnderlyingNetworks(...)` on the service, refresh the
  platform DNS config string (§3.2), and fire `OnDNSConfigChanged` /
  `OnGatewayChanged`. Without this, Wi-Fi↔mobile switches break the
  tunnel. **Required in v0.1**, in reduced form (default network +
  link properties only).
- **State store**: the Go side persists through `AppContext`'s
  encrypted prefs (§3.2); no separate file contract exists.
- **VPNFacade** (`vpnfacade.go`) is Go-internal (router + DNS configurator
  collapsing into `SetBoth` → `updateTUN`); it imposes no Kotlin API
  beyond §3.4, but explains *when* builders are requested: on backend
  state ≥ Starting whenever router/DNS config changes.

## 4. Our Layer 2 contract (summary)

Defined in `app/src/main/java/dev/moriya/vpn/tunnel/TunnelBackend.kt`:

- `TunnelConfig(authKey: String?, hostname: String?, loginServer: String?)`
  — our enrolment input; `loginServer` exists so a Headscale or custom
  control server is a config change, not an architecture change.
- `TunnelState` — `Stopped, NeedsLogin, Starting, Running, Stopping,
  BackendUnavailable, Error`. Backends map their native states onto
  these; UI never sees native state values.
- `TunnelStatus(state, selfAddresses: List<String>, backendName,
  peerCount: Int?, lastError: String?)`.
- `TunnelBackend` — `start(config)`, `connect()`, `disconnect()`,
  `resetEnrolment()`, `status(): TunnelStatus`,
  `addListener/removeListener`, and the VPN-service handshake
  `onVpnServiceRequested(host: TunHost)`, `onVpnServiceDisconnected(id)`,
  `onNetworkChanged(interfaceName, gateway)`.
- `TunHost` / `TunBuilder` / `TunSession` — our mirror of §3.3/§3.4 with
  plain Kotlin types (`TunSession.detachFd(): Int`), so Layer 1 never
  touches a backend's descriptor type.

## 5. Scope — v0.1

Included:

1. Auth-key enrolment only (reusable auth key entered once in the UI,
   stored via the same encrypted-prefs mechanism the backend's state
   store uses; never logged, never in the APK, never in the repo).
2. Connect / disconnect (LocalAPI `start` + `WantRunning` prefs).
3. Status display: state + own tailnet addresses (+ peer count if the
   Notify/Status payload provides it cheaply).
4. `VpnService` shell with foreground notification (status + disconnect
   action), started sticky.
5. Always-on VPN compatible: the service handles the
   `"android.net.VpnService"` system start action, `onRevoke`, and
   restart-after-kill by re-requesting the VPN when enrolment exists —
   mirroring `IPNService.kt`'s start-command branches.
6. Network-change handling in the reduced form of §3.7.
7. Pinned signing from the first CI build (apps-updater pattern:
   keystore decoded from repo secrets in CI), fixed APK name
   `mokavpn.apk`, version + SHA-256 + signer-certificate fingerprint
   presented at handover, per the APK handover standard.

Excluded from v0.1: interactive/browser login, exit nodes, Taildrop and
`ShareFileHelper`, MDM/syspolicy beyond "not configured", hardware
attestation, split-tunnel app selection, quick-settings tile, subnet
routing/advertising, multiple profiles, any UI beyond the one status
screen.

## 6. CI build sequence (drafted in `.github/workflows/build.yml`)

Gradle never runs on the Muse VM (JVM loopback interception — standing
rule); all builds are GitHub Actions on the future public repo.

1. Checkout **this repo** and checkout `tailscale/tailscale-android` at
   a pinned ref into `vendor/tailscale-android` (the AAR's Go module is
   that repository's root module; the ref is recorded and bumped
   deliberately, never floating).
2. Setup Go **1.27.3** (matches upstream `go.mod`), JDK 17.
3. Install Android SDK platform 35 + build-tools 35.0.0 (app template)
   and **NDK 23.1.7779620** (the version upstream's Makefile pins).
4. Install `gomobile`/`gobind` from `golang.org/x/mobile` at the version
   in upstream `go.mod`.
5. Build the AAR, following upstream's Makefile target
   `build-unstripped-aar` + strip/repackage steps:
   `gomobile bind -target android -androidapi 26
   -tags "$(./build-tags.sh) ts_omit_cachenetmap"
   -ldflags "-linkmode=external -extldflags=-Wl,-z,max-page-size=16384 …"`
   → `app/libs/libtailscale.aar`. The `-androidapi 26` and the
   max-page-size flags are NDK-23/16 KB-page specific; changing the NDK
   invalidates them (see Risks).
   **AAR reuse rule (added 2026-11-14, Moriya's suggestion).** We never
   modify upstream code, so the AAR is a pure function of (pinned
   upstream SHA, Go version, NDK version, build tags) and is built at
   most once per such input set. The workflow first tries to download
   a previously built AAR from this repo's GitHub Release
   `libtailscale-<shortsha>` (asset `libtailscale.aar`, verified
   against its companion `.sha256` asset); on a verified hit it skips
   the NDK install and the whole AAR build. On a miss it builds as
   above and publishes the result as that release. A fetch failure is
   always a miss, never a build failure. The AAR is therefore rebuilt
   only when the pinned upstream ref (or Go/NDK/build tags) changes.
   (Upstream publishes no AAR asset itself — only the universal APK —
   so the reuse source is our own earlier build, not a download of
   theirs.)
6. Decode pinned signing keystore from secrets; `./gradlew
   assembleDebug` (the "debug" build type carries the pinned signer in
   this project's template, as in apps-updater).
7. Rename the output to `mokavpn.apk`, record SHA-256 and signer
   certificate (apksigner), upload as the build artifact.
8. Separate `emulator-launch` job repeating steps 1–6, then the
   permanent launch test (`ci/emulator-launch-test.sh`, apps-updater
   pattern): install on an API 35 emulator, launch `MainActivity`,
   assert the process is alive and the activity is top-resumed, fail on
   a `FATAL EXCEPTION` for `dev.moriya.vpn`, capture screenshots and
   logcat as artifacts. A compiled APK that was never launched is not a
   verified app (standing lesson from yt-player).

Note: the emulator is x86_64 while the AAR is built for Android devices
(arm64). The launch test therefore validates Layers 1–2 and the stubbed
adapter only; it must never be read as proof the Go backend runs. Real
backend verification is the moto install trial (§7).

## 7. Verification plan on moto (discriminators)

1. Auth key enrols the device; it appears in the tailnet with a 100.x
   address, with the stock Tailscale app disabled.
2. From the VM, via that address: phone proxy (8080), opencode serve
   (4096) and guest sshd (8022) on moto are reachable.
3. Wi-Fi ↔ mobile switch: tunnel recovers without manual action; note
   the recovery time.
4. Kill the app: with always-on VPN designated, the system restarts the
   service and the tunnel returns.
5. Reboot: tunnel returns without opening the app.
Checks 1–2 prove the route works; 3–5 are the comparison against the
stock app. Before any bulk transfer to moto (the APK is ~25 MB class),
run `wifi-check.sh` per standing procedure; Moriya approved mobile
transfer for the ~25 MB class on 2026-11-14.

## 8. Open questions / risks

- **Upstream API-level drift (found during this preparation).**
  Upstream `android/gradle.properties` now sets `androidApiLevel=36`,
  build-tools 36.0.0, and upstream `minSdk = 26`, while the apps-updater
  template (and this skeleton) uses compileSdk 35 / targetSdk 34 /
  minSdk 29. The gomobile AAR itself is built with `-androidapi 26`, so
  a 35/29 app can consume it in principle, but the combination is
  unverified: the first CI build is the discriminator. If the AAR or
  its manifest merger demands 36, bump compileSdk/targetSdk to 36 in
  the app rather than forcing the AAR down.
- **gomobile version coupling.** Upstream builds gomobile from its own
  `tool/go` wrapper (`go.toolchain.rev` is a tailscale toolchain commit,
  not a Go release). Using the stock Go 1.27.3 + the `x/mobile` version
  from upstream `go.mod` is the plan, but a bind failure there means
  adopting upstream's `tool/go` in CI instead. Unverified until CI runs.
- **NDK 23 pin.** The 16 KB page-size ldflags and the strip/repackage
  dance in upstream's Makefile are written for NDK 23.1.7779620.
  GitHub-hosted runners install it via sdkmanager; do not silently
  substitute a newer NDK.
- **APK growth / ABI.** Upstream repackages the AAR keeping
  `jni/arm64-v8a/libgojni.so`; expected app growth ~15–25 MB (Nov 5
  assessment). x86_64 emulator images get no native backend (§6 note).
- **Auth key handling.** A reusable auth key is a credential. Entry is
  on-device only in v0.1; CI, logs and this repo never see it. Whether
  to also support ephemeral keys for node devices is open.
- **Encrypted prefs — decided (Moriya, 2026-11-14).** Upstream's
  `AppContext` storage uses Jetpack Security's
  EncryptedSharedPreferences (deprecated upstream by Google, still what
  the contract expects semantically). Moriya chose the Keystore option:
  the three pref methods are implemented inside Layer 3 by
  `EncryptedPrefStore` — an AES-256 key in the Android Keystore (alias
  `mokavpn-pref-key`, created on first use), AES/GCM/NoPadding with a
  fresh IV per write, stored as `"v1:" + Base64(iv || ciphertext)` in
  the dedicated `mokavpn-encrypted-prefs` SharedPreferences file; key
  names stay readable so `GetStateStoreKeysJSON` can enumerate them,
  values never appear in the clear. No third-party dependency was
  added. The implementation is production-shaped and needs no rework
  when the AAR wiring lands; until then the adapter still reports
  BackendUnavailable as a whole.
- **LocalAPI JSON models.** Upstream Kotlin models (`Ipn.kt`,
  `IpnState.kt`, `Netmap`…) are coupled to its UI. Layer 3 must define
  its own minimal JSON parsing for Notify/Status/start/prefs rather
  than copying the upstream model package wholesale, or the adapter
  quietly becomes a fork of the upstream app.
- **`Protect` before service start.** Backend sockets created before
  `RequestVPN` are unprotected; upstream works around this with a
  magicsock rebind on VPN request (`backend.go`). The adapter must keep
  that ordering (start backend at process start, request VPN only from
  the service) and not "optimise" it away.
- **Two VPN apps, one slot.** Installing on moto while the stock app
  holds always-on designation will fight; the trial procedure must
  undesignate the stock app first and restore it on rollback.
- **Licence.** libtailscale / tailscale-android are BSD-3-Clause; the
  AAR is built from unmodified upstream source in CI. Any future
  redistribution beyond Moriya's devices should re-check the licence
  and Tailscale's trademark terms.

## 9. Extensions (v1.0.0)

Moriya's founding purpose for this app (stated 2026-11-15): the
stock Tailscale app cannot carry logic of our own, while this app —
designated as the always-on VPN, so the system revives it when
killed — can host a simple helper next to the tunnel. v1.0.0
generalises that helper into an **extension architecture**, so
arbitrary small features (health watching, startup management, a
proxy, …) can be added without touching the tunnel layers.

### 9.1 Where extensions live

Extensions are app-side code in package `dev.moriya.vpn.extensions`
(Layers 1–2 territory). They consume the backend only as Layer 2
`TunnelStatus` — never a Layer 3 type — so the §2 layering rule is
unchanged and the same grep check covers the new package. No new
dependencies: framework APIs only (`HttpURLConnection` for the
health poll).

### 9.2 The contract (`VpnExtension`)

Each extension has:

- a stable `id` and a display name/description (`ExtensionInfo`);
  the id keys the persisted enabled flag and is never renamed;
- `start(context)` / `stop()` lifecycle, driven by the host;
- `onTunnelStatus(status)` — every backend status change, delivered
  only while started;
- `statusText()` — a one-line status for the UI, safe to call in any
  state.

Enabled flags persist in the plain `mokavpn-settings`
SharedPreferences file (the same file as the host-side AdGuard flag;
settings, not secrets) under `extension-enabled-<id>`, via
`ExtensionSettings`. The UI can toggle a flag while the VPN service
is stopped; the host applies it at its next start.

### 9.3 The host (`ExtensionManager`)

`MokaVpnService` owns the manager: created in `onCreate`, started
with the service, stopped in `onDestroy` — extensions live exactly
as long as the VPN service, in the process Android revives for the
always-on VPN. The manager instantiates the built-in extensions
(`Extensions.createAll`), starts those whose flag is on, forwards
backend status to the running set, and is the UI's read/write point
while the service is alive (the service publishes it through a
companion reference; `MainActivity` renders an "Extensions" section
with a switch and a status line per extension).

The built-ins are wired around one shared `HealthResults` channel
owned by the manager: the Health extension publishes poll results
into it, Startup management consumes them. Extensions never
reference each other directly, so either side can change alone.

### 9.4 The built-in extensions

- **Health** (`health`, default on — pure observation). Polls
  `http://127.0.0.1:4096/global/health` (opencode serve on the same
  device) every 60 s with a 5 s timeout; success is HTTP 200.
  Loopback is never routed into the TUN, so the poll measures the
  local stack, not the tunnel. Records last check time/result and
  the consecutive-failure count into `HealthResults`. Cleartext for
  loopback only is permitted by `res/xml/network_security_config.xml`.
- **Startup management** (`startup-management`, default off — it
  acts on another app, so it is opt-in). The helper. When the
  Health channel reports 2 consecutive failures it issues the
  resurrection call, re-issuing at every further 2 consecutive
  failures (attempts at 2, 4, 6, …); it also issues the call once
  when the tunnel first reaches Running while enabled. The call
  mirrors the phone-node helper/waker exactly: an explicit-component
  `startForegroundService` intent for
  `dev.moriya.phonenode/.NodeService`. A resurrection is counted
  **only** when the returned `ComponentName` is non-null — the
  Libero lesson (2026-11-14): without a `<queries>` declaration the
  call silently resolves null on Android 11+, so the manifest
  declares `<queries><package android:name="dev.moriya.phonenode"/>`
  and a null return is shown in the status line instead of being
  swallowed. Platform exceptions (Android 12+ background FGS-start
  restrictions) are likewise recorded, never thrown into the host.

The cascade this completes: system revives the always-on VPN app →
startup management notices the dead stack → phone-node restarts →
its guest supervisor restarts opencode serve.

- **Proxy** (`proxy`, default off — it opens a listening socket),
  added in v1.1.0: see §9.6.
- **ADB keepalive** (`adb-keepalive`, default off — it writes a
  system setting), added in v1.2.0: see §9.7. v1.3.0 extends it
  with the remote debug control endpoint (§9.7).

### 9.5 State-divergence fix (also v1.0.0)

Found on device 2026-11-15: the app could show `State: Running`
(the backend session) while Android Settings showed no VPN — the OS
tunnel had been torn down and the host did not follow. Three fixes:

- `MokaVpnService.onRevoke()` and `onDestroy()` stop the backend
  session (`disconnect()`), so an orderly system-side teardown ends
  the session too. A process kill skips `onDestroy`, so the
  restart-after-kill reconnect path is unaffected.
- The UI shows the OS tunnel state as its own line (`Tunnel:
  up/down`), fed by the process-wide `VpnTunnelState` holder from
  the backend's TUN up/down callbacks — displayed separately from,
  never derived from, the backend session state.
- The auth-key field is masked by construction: `TunnelStatus`
  gained `hasSavedAuthKey` (Layer 3 reports only the fact, never the
  key); when a key is saved the field's hint says so, the field is
  cleared after a start, and an empty field on Connect means "use
  the saved key".

### 9.6 Proxy extension (v1.1.0)

The third built-in extension (`proxy`, default off — it opens a
listening socket, so it is opt-in like startup management): the
forward proxy phone-node carries today, hosted by the VPN app
instead. Clients on the tailnet connect to the proxy; their traffic
leaves through this phone's network, so the egress IP is this
phone's location. Hosted here, the proxy lives in the process
Android revives and is independent of the proot/opencode stack —
it keeps working while that stack is down, which is the same
separation argument as the helper (§9).

Semantics mirror phone-node's native proxy (CONNECT tunnelling
plus plain-HTTP absolute-URI forwarding, 120 s read-idle timeouts,
half-close relay). The code is split by testability:
`ProxyProtocol` holds the protocol decisions as pure functions
(request-line and CONNECT-authority parsing, absolute-URI
splitting, the allowlist decision, proxy-header stripping, the
byte-wise header-block reader) and is covered by plain-JVM tests
in `tests/proxy/`; `ProxyServerCore` is sockets, threads and
counters only. JVM socket tests cannot run on the build VM (its
JVM TCP is intercepted), so the core's transit behaviour is
device-verified only — the same position phone-node's proxy was
in. Concurrency is capped at 32 simultaneous connections to keep
the host process cheap; further accepts are closed and counted as
rejected. Target URLs are never logged.

- **Listening.** Port 8080, bound to the node's tailnet IPv4 from
  `TunnelStatus.selfAddresses` — a specific address, not the
  reference proxy's wildcard, so the listener is not exposed on
  the LAN. It exists only while the extension is enabled AND the
  backend is Running; it follows address changes (rebind) and
  disappears with either condition. So that toggling the extension
  on while the tunnel is already up works, `ExtensionManager` now
  delivers the backend's current status to an extension once,
  right after starting it (also v1.1.0).
- **Allowlist.** Only Moriya's own devices at their current
  tailnet addresses (Muse VM `muse-2` ***REMOVED***, Mac `m1`
  ***REMOVED***, the stock-app `moto` node ***REMOVED***) plus
  loopback; anything else gets 403 and is closed. The list is a
  code constant. It is deliberately NOT copied from phone-node's
  allowlist, which still carries the retired VM address
  ***REMOVED*** — that staleness ends here.
- **Outbound egress — the load-bearing decision.** The proxy runs
  in the VPN app's own process, so an unbound outbound socket
  would follow whatever routing the system applies to our own UID
  — the tunnel's routes today, something else under a future
  configuration (e.g. an exit node). Instead, every outbound
  connection is pinned to the device's underlying (non-VPN)
  network: the host's `DefaultNetworkTracker` pick is passed to
  the extension through `ExtensionManager` as a `() -> Network?`
  provider (a Layer 1 facility, not a backend type — no Layer 2
  contract change), and `NetworkEgress` resolves the target on
  that network, binds the socket with `Network.bindSocket`, and
  connects with a 10 s timeout. This is exactly the treatment
  Layer 3 gives the Go backend's dials (same tracker, same bind
  operation). When no underlying network is known, the connection
  is refused (502) rather than dialled unbound. A consequence, by
  design: proxy destinations inside the tailnet itself are also
  attempted on the physical network and simply fail — the proxy
  is an internet-egress tool, not a tunnel shortcut.
- **Coexistence with phone-node's proxy.** That proxy binds the
  wildcard address on the same port 8080, which blocks any other
  bind of the port on the device — so the two proxies cannot run
  at once on one phone. When the bind fails, this extension
  records the error in its status line (never throws into the
  host service) and retries every 15 s and on every tunnel status
  change, so its listener appears if phone-node's proxy later
  stops. In practice a node runs one proxy or the other; this one
  is the survivor when the node stack is down.

### 9.7 ADB keepalive extension (v1.2.0)

The fourth built-in extension (`adb-keepalive`, default off — it
writes a system setting, so it is opt-in like startup management).
It guards the node phones' adb access, which the survival cascade
does not cover: adb is how a wedged node is inspected and repaired
from the VM, and it has a failure mode of its own.

Background, measured on moto g05 (Android 15) on 2026-11-16. Wired
USB debugging is dead on that device while Android runs, so adb
goes over the network: wireless debugging is paired once, then
`adb tcpip 5555` fixes adbd's listener to port 5555 on all
interfaces, reachable at the node's tailnet address. Two measured
properties make that setup almost self-healing: the 5555 listener
configuration (`service.adb.tcp.port`) survives adbd restarts, and
the listener revives on its own once adbd runs again. But adbd
runs only while at least one debug toggle is on — and wireless
debugging sometimes turns itself off (it did so repeatedly on the
Libero), at which point adbd stops and there is no remote way back
in: the very tool needed for repair is what died. The steady state
Moriya chose is wireless ON / USB OFF, because many apps refuse to
start while USB debugging (`adb_enabled`) is on; the wireless
toggle carries no such problem.

- **What it watches.** Every 60 s (the Health extension's cadence)
  it reads Settings.Global `adb_wifi_enabled`. That is the only
  setting the watcher ever reads or writes: **`adb_enabled` is
  never touched by any automatic logic** — turning USB debugging
  on autonomously would reintroduce the app-refusal problem the
  wireless-only setup exists to avoid. Since v1.3.0 the one
  exception is deliberate, not automatic: the control endpoint
  below writes `adb_enabled`, but only on an explicit command
  that has passed its allowlist and token checks (see **Remote
  debug control**). The separation is structural — the watcher's
  decision logic has no path to that key.
- **The decision** lives in `AdbKeepaliveLogic` as a pure function
  of (toggle value, grant held, Wi-Fi connected), covered by
  plain-JVM tests in `tests/adb-keepalive/`; the extension class
  is the Android-facing shell. A write happens only in the one
  state where it is needed and known to stick — toggle off, grant
  held, Wi-Fi connected — and restores are counted with the last
  restore time for the status line. Toggle on: nothing. Toggle
  unreadable (never written on the device, or a provider error):
  nothing — an unknown state is never written.
- **The Wi-Fi gate.** When Wi-Fi is down the system drops the
  toggle and will not keep a restored value, so writing would be a
  fight the extension cannot win; it waits instead. The check must
  describe the physical network, not the tunnel — this app is
  itself the VPN, so the system's default network may be our own
  TUN. It therefore reuses the host's underlying-network pick
  (the `DefaultNetworkTracker` selection, NOT_VPN by construction,
  passed through `ExtensionManager` as the same
  `() -> Network?` provider the Proxy's egress binds to) and reads
  that network's transports: Wi-Fi means `TRANSPORT_WIFI` on the
  underlying network; on mobile data the pick is cellular and the
  gate stays closed.
- **The grant model.** Writing Settings.Global needs
  `WRITE_SECURE_SETTINGS`. The manifest declares it, but the
  system never grants it automatically; it is granted once by hand
  after install: `adb shell pm grant dev.moriya.vpn
  android.permission.WRITE_SECURE_SETTINGS` (run from the VM over
  the tailnet adb connection, coordinated with Moriya — the grant
  is deliberately not something the app can do for itself). The
  declaration is the load-bearing half: against v1.1.0, which did
  not declare it, the same `pm grant` failed outright (measured
  2026-11-16). Grant state is checked with `checkSelfPermission`
  before any write is considered; without the grant the decision
  is NO_GRANT and no write is attempted. The status line always
  shows the toggle value, grant held or NOT held, Wi-Fi state,
  restore count and last restore time, and while the grant is
  missing it also shows the grant command itself, so the fix is
  discoverable from the UI. A write that still throws
  `SecurityException` (grant revoked between check and write) is
  recorded in the status line, never thrown into the host.
- **Remote debug control (v1.3.0).** The gap the watcher cannot
  cover: wireless debugging needs Wi-Fi, so on mobile data adbd
  stops and tailnet adb dies — while the tunnel itself stays
  alive (both measured on moto g05, 2026-11-16). Moriya's
  workaround is to turn USB debugging on as needed: adbd revives
  and the persisted 5555 listener returns with it, no `tcpip`
  re-run needed. The endpoint makes that switch reachable from
  the VM over the tunnel. While the extension is enabled, a small
  HTTP listener is bound to the node's tailnet IPv4 (from
  `TunnelStatus.selfAddresses`, never the wildcard, so it is not
  exposed on the LAN) on port **8091**, only while the backend is
  Running; it follows address changes and retries a failed bind
  every 15 s — the Proxy extension's listening discipline, at a
  smaller scale (concurrency capped at 8, 30 s idle timeouts).
  The code splits by testability as before: `AdbControlProtocol`
  holds the authorization decision as pure functions (covered by
  plain-JVM tests in `tests/adb-control/`), `AdbControlServerCore`
  is sockets and audit counters only, and the settings writes
  live in the extension class. Exactly three commands exist —
  `POST /adb-enabled/on` and `POST /adb-enabled/off` (write
  `adb_enabled` 1/0 and answer with the read-back value) and
  `GET /status` (answers `adb_wifi_enabled`, `adb_enabled` and
  the grant state as plain text) — and nothing else: there is no
  generic settings or shell surface. Responses are 200 on
  success, 403 for allowlist/token failures, 404 for anything
  else; a write refused by the system (`SecurityException`, or
  the grant missing) answers 500, is recorded in the status line,
  and never crashes the host.
- **Authorization: allowlist AND token, both required.** The
  source address must be on the Proxy extension's allowlist
  (Moriya's own devices at their current tailnet addresses, plus
  loopback) AND the request must carry the per-device token in
  an `X-Moka-Token` header, compared in constant time; either
  factor alone authorizes nothing, and an unknown path or wrong
  method is only distinguishable after both pass. The token is
  generated once on the device (32 secure-random bytes, hex)
  when the extension first starts, persisted in the plain
  `mokavpn-settings` prefs (app-private), and shown in the
  extension's status line: the owner's handover channel is
  reading it off the phone screen once and saving it on the VM.
  There is no regenerate UI in this version; clearing the app's
  data is the rotation path. The status line also audits the
  endpoint: listener state (bound address:port or the bind
  error), the last accepted command (action, source, time), and
  a count of denied attempts.
- **VM-side helper.** `tools/moka-adb-debug on|off|status` reads
  the token from `~/.config/moka-vpn/adb-debug-token` (mode 600;
  the script fails with instructions when the file is missing
  and never invents a value) and calls
  `http://***REMOVED***:8091/...` through the tunnel proxy,
  derived from `HTTPS_PROXY` exactly as the adb relay derives it
  (`"${HTTPS_PROXY%:*}:3130"`). The node address is the current
  trial node's; the endpoint itself binds whatever tailnet IPv4
  the backend reports.
- **Limitation, stated plainly.** This extension guards the
  toggle, not the listener configuration. After a device reboot,
  the `adb tcpip 5555` configuration itself is gone
  (`service.adb.tcp.port` does not survive a reboot), so the
  extension alone cannot restore 5555: one wireless-debugging
  connect plus an `adb tcpip 5555` re-run is needed first. The
  control endpoint has the same boundary: after a reboot it can
  still turn `adb_enabled` on, but with the tcpip configuration
  gone there is no 5555 listener for the VM to reach, so VM
  reachability is not restored — the endpoint covers the
  adbd-stopped case (mobile data, a spontaneously dropped
  toggle), not the reboot case. The watcher also cannot restore
  anything while Wi-Fi is down, and like every extension both
  halves only run while the host service lives — the always-on
  designation is its anchor, as for the rest of §9.

# Moka VPN

Standalone Android VPN app embedding libtailscale, with our own thin
UI and VpnService shell replacing the stock Tailscale app's shell.
Package `dev.moriya.vpn`, fixed APK name `mokavpn.apk`.

**Start with [DESIGN.md](DESIGN.md)** — goal, the 3-layer architecture
(UI/VpnService shell → `TunnelBackend` contract → libtailscale adapter,
the only layer that touches libtailscale), the exact host contract,
v0.1 scope, CI build sequence, and open risks.

Status (2026-11-14): public repo `ysk-agent/moka-vpn`; the first CI
build is the current step. The app still runs against a Layer 3 stub
that honestly reports "backend not integrated" — the real AAR is
built in CI (`.github/workflows/build.yml`) but the adapter does not
wire its calls yet. The AppContext encrypted prefs are already
implemented for real (Android Keystore AES-GCM, Layer 3; DESIGN.md
§8). No Drive publication yet.

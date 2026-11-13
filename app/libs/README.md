# app/libs

CI drops `libtailscale.aar` here before the Gradle build
(see `.github/workflows/build.yml` and `DESIGN.md` §6).
The AAR is a build artifact of unmodified upstream
`tailscale/tailscale-android` source: it is gitignored and must
never be committed. Without it, the app builds against the
Layer 3 stub and reports "backend not integrated".

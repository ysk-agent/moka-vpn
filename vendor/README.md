# vendor/

CI checks out `tailscale/tailscale-android` at a pinned ref into
`vendor/tailscale-android/` (gitignored) and builds `libtailscale.aar`
from it. Nothing is vendored in git. The ref CI uses lives in
`.github/workflows/build.yml` (`TAILSCALE_ANDROID_REF`) and must be
bumped deliberately, with DESIGN.md §8 re-checked at each bump.
For local reference this preparation was written against the shallow
clone at `~/workspace/tailscale-android-src/`.

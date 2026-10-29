# AlWarraqa Android — v2 source & APK workflow
This isolated Android module is separate from the Beam-Ollama-Tablet app.
- Android source: `AlWarraqa-2.0-Android-Source.tar.gz` (unpack to repository root).
- CI: `.github/workflows/alwarraqa-v2.yml`.
- Package: `com.alwarraqa.app`.
- Version: `2.0.0`.
- Debug signing **does not match** the original v1 APK. Back up v1 before considering a fresh installation.
- Includes a non-destructive SQLite migration for in-place builds **only when signing certificates match**.

This is a review branch. Do not merge without passing Android CI and functional validation on physical devices.

# Studio Mobile for aistudio-api

Android 8+ native Kotlin / Jetpack Compose client. The existing server remains unchanged.

## Client build (first milestone)
Install JDK 17, Android SDK 35 and Gradle 8.7, then:
```sh
cd android
gradle :app:assembleDebug
```
APK: app/build/outputs/apk/debug/app-debug.apk. Connect to an authorized Gemini-compatible v1beta endpoint; HTTPS is required for non-loopback endpoints. The official Gemini API is also supported with your own API key.

## Experimental embedded build
The CI workflow first builds the pinned pydantic-core 2.41.5 Android wheel from its verified PyPI source archive using cibuildwheel and Rust, and places it in android/wheels. For local builds, prepare the same wheel and install Python 3.13. From repository root, build the upstream Vue management UI with Bun first:
```sh
cd web && bun install --frozen-lockfile && bun run build
cd ../android
gradle -Pembedded=true :app:assembleDebug
```
The embedded variant attempts to include the original FastAPI code with Chaquopy, Python 3.13 and arm64-v8a. It binds only to localhost with randomly generated local API and admin credentials. It is **not yet validated** on an Android device. Native dependency availability (especially the exact pydantic-core version), Python lifecycle and the upstream signer are blocking compatibility checks. Does not add new account rotation or signing logic. Network is still required for model generation. Only use endpoints and accounts you are authorized to access; upstream proxy reliability and policies may change.

## Implemented
Native conversation view, streaming SSE text, multi-turn context, stop button, dynamic model discovery, model selection, encrypted API key storage, local gateway controls (experimental), existing browser management panel. Chat history is in memory; image/file attachments and advanced management UI are planned. No secrets should be committed.

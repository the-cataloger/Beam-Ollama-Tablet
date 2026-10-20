# Beam Space — Ollama Cloud Android WebView app

**Android tablet client** for the Beam cloud model through Ollama's hosted API. Arabic RTL tablet UI, local chat history, streaming responses, stop/copy/export, and adjustable system prompt/temperature.

## Build an APK
Go to [Actions](https://github.com/the-cataloger/Beam-Ollama-Tablet/actions/workflows/android.yml), open **Build Android APK**, and download the `BeamTablet-debug-apk` artifact after a successful run. Unzip it and install `app-debug.apk` on your Android tablet.

No Android Studio is needed to use the GitHub Actions build. Debug APKs are for testing, not release distribution.

## Sign in
Create an API key at https://ollama.com/settings/keys and enter it **only inside the app**. Cloud API requests go to `https://ollama.com/api/chat` with `model: "beam"`. The key is encrypted using Android Keystore (AES-GCM). Do not place API keys in commits or GitHub Actions variables.

## Security
- API calls are made by native Android HTTPS code; JavaScript does not get the API token.
- The WebView displays packaged local assets, with a Content Security Policy and no third-party scripts.
- Conversations are stored in ordinary WebView localStorage **without at-rest encryption** and chat context is sent to Ollama Cloud. Do not send sensitive institutional records without authorization.
- Beam cloud availability/pricing can change; temporary free availability does not guarantee unrestricted free access.
- Cloud connection is not yet verified against a real user key.

[Arabic setup guide](README_AR.md) · [Ollama API docs](https://docs.ollama.com/api/introduction)

# Codex login on BOOX

Version 0.2.0 adds a **Codex (ChatGPT login)** provider. The Android app talks directly to OpenAI; no OpenClaw installation, proxy, API key, or desktop process is required.

## Use

1. Long-press the diary with a finger to open Settings; select **Codex (ChatGPT login)**.
2. Choose **Sign in with device code (recommended)**. Open the displayed OpenAI URL on your phone, Mac, or BOOX and enter the code. If required, enable device code authentication in ChatGPT security settings (managed workspaces may need admin permission).
3. Alternatively choose **Sign in on this BOOX**, complete the sign-in in the system browser, and return to the diary. Keep the app/settings open while signing in. A killed app or dismissed dialog cancels the attempt; start again in that case.
4. After sign-in, the app fetches the account's Codex model catalog. Choose a model, then **Save**. Write on the page as usual.
5. **Refresh model list** fetches the catalog again. Your last model selection is remembered. Models explicitly marked text-only or hidden are omitted. Older catalog entries without modality metadata follow Codex's text-and-image default. Actual model access is enforced by OpenAI on each request.

**Sign out on this device** removes the stored session and cached model choice. It does not revoke every session of your ChatGPT account. If login expires or is revoked, sign in again. Usage-limit and access-denied errors are shown in the app.

## Implementation

- Device code: OpenAI `/api/accounts/deviceauth/usercode` and `/deviceauth/token`, then authorization-code exchange at `/oauth/token`. Polling respects the server interval and expires after 15 minutes.
- Browser: authorization code + S256 PKCE, random state, and the Codex loopback callback `http://localhost:1455/auth/callback`. The receiver binds only to IPv4 loopback and closes after success, cancellation, or timeout. Uses the system browser; no embedded password form.
- Uses the public Codex OAuth client ID and minimal identity/offline scopes found in OpenClaw's implementation. This is a native Kotlin protocol implementation, not a separately registered SIWC application. Service changes can require updates.
- Stores access/refresh tokens as AES-GCM ciphertext using an Android Keystore key, in `noBackupFilesDir`. Token responses and request bodies are not logged. No credentials are embedded in the APK or workflow.
- Refreshes expiring sessions, serializes refresh against logout, and retries an HTTP 401 once with refreshed credentials. Does not automatically retry a streamed generation, avoiding duplicate requests.
- Fetches `/backend-api/codex/models` with a catalog compatibility version; sends handwriting PNGs to `/backend-api/codex/responses` with `store: false` and streaming enabled. A completed response is required before showing the reply.
- Model authentication does not grant the diary access to Codex tools, files, connected apps, or ChatGPT conversation history.

## Build and checks

`./gradlew testDebugUnitTest assembleDebug` (JDK 17 and Android SDK 34).

GitHub Actions remains **workflow_dispatch only**: choose **Build debug APK → Run workflow → main**. The selected commit is tested and built; download the `boox-riddle-debug-apk` artifact. A debug APK is suitable for sideloading. Different debug signing keys (local build vs GitHub runner) may require uninstalling an existing installation before installing the other build; that removes local app settings.

Unit tests cover PKCE, callback state validation, model filtering, image request format, refusal handling, and stream completion/truncation. Building and unit tests do not prove successful sign-in, quota access, or pen behavior on a physical BOOX; those require testing with your account on the device.

## Protocol references

- [OpenAI Codex authentication](https://learn.chatgpt.com/docs/auth)
- [OpenClaw browser authorization](https://github.com/openclaw/openclaw/blob/main/extensions/openai/openai-chatgpt-oauth-authorization.runtime.ts)
- [OpenClaw device-code flow](https://github.com/openclaw/openclaw/blob/main/extensions/openai/openai-chatgpt-device-code.ts)
- [OpenClaw token exchange and refresh](https://github.com/openclaw/openclaw/blob/main/extensions/openai/openai-chatgpt-oauth-token.runtime.ts)
- [Codex model catalog](https://github.com/openai/codex/blob/main/codex-rs/codex-api/src/endpoint/models.rs)

## Language selection (0.3.0)

Settings → App language → English / Tiếng Việt / Follow device language → Save. The app refreshes Settings immediately and remembers the choice. Both languages cover provider settings, login controls, status, error messages and the app-owned browser callback. Model identifiers, URLs and provider names retain their official spelling.

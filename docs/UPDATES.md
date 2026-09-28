# Stable updates / Cập nhật giữ dữ liệu

Starting with 0.4.0, distribute a **release APK signed with the same private key**.
Keep `applicationId = com.billtt.riddle`; increase `versionCode` for every update.
Install the new APK over the old app (or `adb install -r file.apk`). Do not uninstall
or clear app data. Android then retains preferences, local conversations and the
Keystore key protecting Codex tokens. Login can still expire or be revoked by the
provider; signing cannot prevent that.

## This checkout

The private key and its password are outside Git:
`~/.config/boox-riddle-signing/release.jks` and `keystore.properties`.
Back up this entire directory securely. Losing the private key prevents future
updates to an installation signed with it. Do not upload these files to the repository.
Gradle reads this default path, or the `BOOX_SIGNING_PROPERTIES` environment variable.
`./gradlew testDebugUnitTest assembleRelease` produces the signed release locally.

## GitHub Actions (manual only)

Create a repository Actions secret named `ANDROID_SIGNING_BUNDLE`. Its value is the
contents of `~/.config/boox-riddle-signing/github-secret.txt` (base64 JSON containing
`keystore` as base64, `password`, and `alias`). The workflow decodes it only into
runner temporary storage and removes it after building. The key is not an artifact.
Without the secret, the job fails instead of silently making an incompatible APK.
Only `workflow_dispatch` on main is allowed. Checkout uses `github.sha`, the commit
selected at dispatch time. Download `boox-riddle-release-apk` after success.

## One-time transition from older builds

The previously distributed 0.2.0 and 0.3.0 GitHub debug APKs used ephemeral runner
keys, and their signing certificates differ. Their private keys were not archived.
The new release key cannot replace those signatures. A normal in-place upgrade
from either old APK is therefore unavailable: one uninstall/reinstall and login
will be needed. Old releases had no conversation archive. Future stable-release
updates preserve local data without this reinstall step.

## Storage and privacy

Conversation JSON, handwritten PNGs and drafts remain in the app's private,
backup-excluded directory. They are not encrypted separately; Android app isolation
protects them on an ordinary device. Codex credentials remain encrypted with Android
Keystore. Application backup is disabled, including plaintext API-key preferences.
Deleting a conversation removes its archive and images. Uninstalling/clearing data
removes everything. Exported TXT files contain conversation text, not handwriting
images or credentials. A local note is excluded from AI requests.

Context percentages use a conservative estimate (UTF-8 text bytes / 3 plus 4,096 per
image) against a user-configured budget (default 32,000). They are not provider usage
or an automatically discovered model limit. Continuous mode includes previous turns;
above 70% of budget or eight images, the app summarizes older turns with the selected
provider while retaining two recent exchanges. Compression uses an extra AI request.
All original messages stay in history. Summary quality and handwriting recognition
are model-dependent; failed requests remain archived and can be retried.

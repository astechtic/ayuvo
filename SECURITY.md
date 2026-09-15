# Security

## Reporting a vulnerability

Report privately to **yaaratech@gmail.com** with the subject line "Security". Please include the platform and
app version (Settings → App & Updates), steps to reproduce, and impact. We acknowledge reports within
7 days and aim to ship fixes in the next store release.

Please do not open public issues or post details elsewhere before a fix is available.

## Supported versions

The latest versions on the App Store and Google Play.

## Scope

- API-key storage (iOS Keychain, Android EncryptedSharedPreferences) and any path that could expose keys
- Networking to AI/speech providers, Open Food Facts, GitHub (exercise media) and Hugging Face (model
  downloads), including TLS handling and the local-network HTTP exception for Ollama
- Apple Health / Health Connect integration and the local health database (backup exclusion, deletion)
- The optional iCloud / Google Drive backup archive
- Widgets, Apple Watch and the Share Extension (app group container)
- The website's headers and content (static, no server code)

## Out of scope

- Third-party provider services, Open Food Facts data quality, and the operating systems themselves
- Issues that require a jailbroken/rooted device or physical access to an unlocked phone
- Keys the user chose to share or paste elsewhere

# Project Instructions

- User communicates in Bengali; reply in English.
- IMPORTANT — NO LOCAL BUILDS: Never run any APK/build/Gradle command on this machine
  (no `gradlew`, `assembleRelease`, `testDebugUnitTest`, etc.). All builds run on the
  user's GitHub account via the `ci.yml` GitHub Actions workflow on push to main.
  Commit and push, then check the workflow run. Local verification is limited to
  inspecting source and git history.

## Build & Test Commands

When code changes are needed, run these for verification:
- `./gradlew ktlintCheck` — lint check
- `./gradlew detekt` — static analysis
- `./gradlew testDebugUnitTest` — unit tests
- `./gradlew assembleDebug` — debug build

## Security Notes

- Signing keys are loaded from environment variables (KEYSTORE_FILE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)
- Never commit keystore files, passwords, or local.properties to the repository
- CI/CD uses GitHub Secrets for signing configuration

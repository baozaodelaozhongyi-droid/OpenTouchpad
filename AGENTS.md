# OpenTouchpad Agent Guidelines

## Local Build & Test
- For local verification after code changes, run **only** debug unit tests:
  ```bash
  gradle --no-daemon testDebugUnitTest
  ```
- **Avoid running `gradle test assembleRelease` locally** unless explicitly requested:
  - Local environments may be memory-constrained (causing slow swap/OOM or task interruptions).
  - Local release APKs are unsigned without CI keystore secrets; signed release APKs are built in GitHub Actions.
- Keep project `gradle.properties` untouched for CI; machine-specific low-memory overrides (`org.gradle.jvmargs=-Xmx768m`, `org.gradle.workers.max=1`, `kotlin.compiler.execution.strategy=in-process`) belong in `~/.gradle/gradle.properties`.

## Release Workflow
1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `CHANGELOG-v<version>.md` at the repository root.
3. Run `gradle --no-daemon testDebugUnitTest` and confirm all unit tests pass.
4. Commit changes, create tag `v<version>`, and push both `main` and the tag:
   ```bash
   git push origin main v<version>
   ```
5. `.github/workflows/release.yml` automatically runs `gradle --no-daemon test assembleRelease`, signs the APK using repository secrets, renames it to `OpenTouchpad-v<version>.apk`, and publishes the GitHub Release using `CHANGELOG-v<version>.md`.

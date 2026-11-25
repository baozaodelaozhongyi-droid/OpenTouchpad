# OpenTouchpad Agent Guidelines

## Local Build & Test — HARD RULE (scoped to the 1 GB VPS host only)
- **On this VPS host (`ser331458405279`, 1 vCPU / ~1 GB RAM): NEVER run Gradle / any JVM build or test.** It has 1 vCPU and ~1 GB RAM shared with other services. Starting Gradle (even `testDebugUnitTest`, even with `--no-daemon`) forks JVMs, drives the box into swap, and gets java OOM-killed — which takes the whole host (and the Telegram bot) down with it.
  - Incident 2026-11-25 16:23: `java` OOM-killed during `gradle test`, host swap-thrashed for minutes, bot couldn't poll Telegram.
  - **Exempt:** GitHub Actions runners and agy **remote/cloud agents** (`--remote-agent`) do not run on this host — they have their own cloud environment and MUST run `gradle test assembleRelease` normally there.
- **Build and test only in GitHub Actions**:
  ```bash
  gh workflow run build.yml            # compile + unit tests
  gh run watch <run-id> --exit-status  # wait for the result
  ```
  Push to `main` / a tag also triggers CI automatically.
- Allowed locally: reading/editing code, `git`, `grep`, `diff`, plain Python/shell that does not start a JVM.
- If a local JVM run is truly unavoidable, **ask the user first**, run one at a time, with `GRADLE_OPTS="-Xmx256m" --no-daemon`.
- Keep project `gradle.properties` untouched for CI; machine-specific overrides belong in `~/.gradle/gradle.properties`.

## Release Workflow
1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `CHANGELOG-v<version>.md` at the repository root.
3. Do **not** run tests locally: push `main` (or the tag) and let CI run `gradle --no-daemon test`; wait for it to go green with `gh run watch <id> --exit-status` before/while tagging.
4. Commit changes, create tag `v<version>`, and push both `main` and the tag:
   ```bash
   git push origin main v<version>
   ```
5. `.github/workflows/release.yml` automatically runs `gradle --no-daemon test assembleRelease`, signs the APK using repository secrets, renames it to `OpenTouchpad-v<version>.apk`, and publishes the GitHub Release using `CHANGELOG-v<version>.md`.

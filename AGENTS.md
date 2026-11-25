# OpenTouchpad Agent Guidelines

## Local Build, Test & Memory — HARD RULE (scoped to the 1 GB VPS host only)
- **On this VPS host (`ser331458405279`, 1 vCPU / ~967 MB RAM, ~100–180 MB free): NEVER run Gradle / any JVM build or test.** Starting Gradle (even `testDebugUnitTest`, even with `--no-daemon`) forks JVMs, drives the box into swap, and gets `java` OOM-killed — taking the whole host (and Telegram/Hermes/agy services) down with it.
  - Incident 2026-11-25 16:23: `java` OOM-killed during `gradle test`, host swap-thrashed for minutes, bot couldn't poll Telegram.
  - **Do NOT run heavy multi-agent `/swarm` locally on `ser331458405279`**; work single-threaded locally or offload heavy tasks to the Cloud Remote Agent / GitHub Actions below.
  - **Exempt:** GitHub Actions runners and Antigravity **Cloud Remote Agents** (`--remote-agent`, `hostname = devbox`, ~7.8 GiB RAM) do not run on this host — they have their own cloud environment and MUST run `gradle --no-daemon test assembleRelease` normally there.
- Allowed locally on `ser331458405279`: reading/editing code, `git`, `gh`, `grep`, `diff`, plain Python/shell that does not start a JVM.
- If a local JVM run on `ser331458405279` is truly unavoidable, **ask the user first**, run one at a time, with `GRADLE_OPTS="-Xmx256m" --no-daemon`.
- Keep project `gradle.properties` untouched for CI; machine-specific overrides belong in `~/.gradle/gradle.properties`.

## Cloud Build, Test & Remote Agent Offloading

### 1. GitHub Actions CI (`gh` is pre-authenticated on `ser331458405279`)
- `.github/workflows/build.yml` runs **both unit tests and release APK build** (`gradle --no-daemon test assembleRelease`) on `push` to `main`, `pull_request`, and `workflow_dispatch`.
- `gh` CLI is already logged in on `ser331458405279` via `~/.config/gh/hosts.yml` (`repo`, `workflow` scopes; ignore the harmless `Missing required token scopes: 'read:org'` notice in `gh auth status`).
  ```bash
  gh workflow run build.yml                                              # compile + unit tests + APK
  RUN_ID=$(gh run list --limit 1 --json databaseId -q '.[0].databaseId')
  gh run watch "$RUN_ID" --exit-status                                   # wait for result
  ```

### 2. Proactive Cloud Remote Agent (`baozaodelaozhongyi-droid/OpenTouchpad`)
- The cloud project **`baozaodelaozhongyi-droid/OpenTouchpad`** is already configured and runs in a ~7.8 GiB RAM cloud container (`devbox`).
- From `ser331458405279`, you can proactively dispatch heavy tasks (multi-file refactors, local Gradle test loops, etc.) to the cloud agent:
  - `--remote-agent` does **not** support `-p` / `--print` yet; invoke `-i` (`--prompt-interactive`) via a small Python `pty` wrapper:
    ```bash
    agy --remote-agent --project "baozaodelaozhongyi-droid/OpenTouchpad" --model <model-id> -i "<prompt>"
    ```
  - Pass `--model <model-id>` to pick any model from `agy models` (e.g. `gemini-4-hydrogen-high`, `gemini-4-carbon-high`, `gemini-4-neon-high`, `claude-opus-5-5-high`).
  - Cloud agents work on the GitHub remote repo (not uncommitted files on `ser331458405279`), so sync via `git push` / `git pull`, and always kill/clean up the local PTY thin-client process group (`\x04\x04` + `SIGTERM`) in `finally`.

## Release Workflow
1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `CHANGELOG-v<version>.md` at the repository root.
3. Do **not** run tests locally on `ser331458405279`: push `main` (or trigger `build.yml`) and wait for CI (`gradle --no-daemon test assembleRelease`) to go green with `gh run watch <id> --exit-status`.
4. Commit changes, create tag `v<version>`, and push both `main` and the tag:
   ```bash
   git push origin main v<version>
   ```
5. `.github/workflows/release.yml` automatically runs `gradle --no-daemon test assembleRelease`, signs the APK using repository secrets, renames it to `OpenTouchpad-v<version>.apk`, and publishes the GitHub Release using `CHANGELOG-v<version>.md`.

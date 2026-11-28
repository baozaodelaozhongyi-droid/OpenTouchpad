# OpenTouchpad Agent Guidelines

## Local Build, Test & Memory — HARD RULE (scoped to the 1 GB VPS host only)
- **On this VPS host (`ser331458405279`, 1 vCPU / ~967 MB RAM, ~100–180 MB free): NEVER run Gradle / any JVM build or test.** Starting Gradle (even `testDebugUnitTest`, even with `--no-daemon`) forks JVMs, drives the box into swap, and gets `java` OOM-killed — taking the whole host (and Telegram/Hermes/agy services) down with it.
  - Incident 2026-11-25 16:23: `java` OOM-killed during `gradle test`, host swap-thrashed for minutes, bot couldn't poll Telegram.
  - **Do NOT run heavy multi-agent `/swarm` locally on `ser331458405279`**.
  - **Exempt:** GitHub Actions runners and Antigravity **Cloud Remote Agents** (`--remote-agent`, `hostname = devbox`, ~7.8 GiB RAM) do not run on this host — they have their own cloud environment and MUST run `gradle --no-daemon test assembleRelease` normally there.
- Allowed locally on `ser331458405279`: reading/editing code, `git`, `gh`, `grep`, `diff`, plain Python/shell (`< 50 MB` RAM, zero JVM).
- If a local JVM run on `ser331458405279` is truly unavoidable, **ask the user first**, run one at a time, with `GRADLE_OPTS="-Xmx256m" --no-daemon`.
- Keep project `gradle.properties` untouched for CI; machine-specific overrides belong in `~/.gradle/gradle.properties`.

## 3-Tier Task Routing Matrix (for sessions on `ser331458405279`)
1. **Tier 1 — Local Only (zero cloud overhead):**
   - Q&A, code walkthroughs/diagnosis, docs/config updates, or small targeted fixes (`≤ 3` files, clear logic, no iterative compile/test loop needed).
2. **Tier 2 — Local Edit + GitHub Actions (single verification / release):**
   - Changes that can be written cleanly in one pass locally and only need a **single** `gradle --no-daemon test assembleRelease` verification run or signed release APK build.
3. **Tier 3 — MUST Dispatch to Cloud Remote Agent (`devbox`, ~7.8 GiB RAM) if ANY apply:**
   - Needs multi-round **edit ↔ `gradle test` / compile** iteration (e.g., complex gesture/physics/state-machine refactors, fixing tricky compile errors, writing large unit test suites);
   - Touches **`≥ 4` core source files** or the user asks for `/swarm` / multi-agent parallel development or full-repo review;
   - Requires any JVM tool, heavy decompilation/reverse-engineering, batch image/asset processing, or **`> 50 MB` extra RAM**.

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
- The cloud project **`baozaodelaozhongyi-droid/OpenTouchpad`** runs in a ~7.8 GiB RAM cloud container (`devbox`).
- From `ser331458405279`, proactively dispatch all **Tier 3** tasks to the cloud agent:
  - `--remote-agent` does **not** support `-p` / `--print` yet; invoke `-i` (`--prompt-interactive`) via a small Python `pty` wrapper:
    ```bash
    agy --remote-agent --project "baozaodelaozhongyi-droid/OpenTouchpad" --model <model-id> -i "<prompt>"
    ```
  - **Difficulty-based model allocation (`--model <model-id>`):** Unless the user specifies a model, allocate by task difficulty (do **not** use `Orchestrator` models on remote agents):
    - **Light / Routine** (quick compile/test fix, mechanical batch edits): `gemini-3.8-flash-high` or `gemini-4-hydrogen-medium` (subagents: `inherit` / `flash_lite`).
    - **Standard Engineering** (multi-file feature, UI/shader/animation work, unit test suites): `gemini-4-hydrogen-high` or `gemini-4-carbon-medium` (core/verifier subagents: `inherit`; search subagents: `flash`).
    - **Hard / Complex Bugs & Architecture** (concurrency/deadlocks, intricate gesture/physics math, deep VRR/memory optimization): `gemini-4-carbon-high` or `gemini-4-neon-high` — or fallback to `claude-opus-5-5-high` / `claude-sonnet-5-5-high` when Gemini quota is tight (core/verifier subagents: `inherit` / `pro`; helper subagents: `flash`).
  - **Multi-agent note (verified):** Remote Agents do **not** support the `/swarm` message-board slash command yet, **but DO support parallel `invoke_subagent` / `manage_subagents` subagents** inside the 7.8 GiB `devbox`.
  - Cloud agents work on the GitHub remote repo (not uncommitted files on `ser331458405279`), so sync via `git push` / `git pull`, and always kill/clean up the local PTY thin-client process group (`\x04\x04` + `SIGTERM`) in `finally`.

## Release Workflow
1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `CHANGELOG/CHANGELOG-v<version>.md`.
3. Do **not** run tests locally on `ser331458405279`: push `main` (or trigger `build.yml`) and wait for CI (`gradle --no-daemon test assembleRelease`) to go green with `gh run watch <id> --exit-status`.
4. Commit changes, create tag `v<version>`, and push both `main` and the tag:
   ```bash
   git push origin main v<version>
   ```
5. `.github/workflows/release.yml` automatically runs `gradle --no-daemon test assembleRelease`, signs the APK using repository secrets, renames it to `OpenTouchpad-v<version>.apk`, and publishes the GitHub Release using `CHANGELOG/CHANGELOG-v<version>.md`.

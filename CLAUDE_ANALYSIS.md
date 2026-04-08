# Kai OS Runtime — Audit Reports

**Project**: kai-bubble
**Auditor**: Claude Sonnet 4.6
**Method**: Direct source read — all prior reports treated as untrusted

---

## TREE AUDIT (2026-04-06)

*(Full tree audit content — see prior session)*

---

## API CONFIGURATION AUDIT

**Audit Date**: 2026-04-06
**Scope**: OpenAI key, Supabase URL + anon key, BuildConfig wiring, environment resolution
**Status**: READ-ONLY — no changes made

---

### 1. OpenAI API Key

#### Where it is defined

| Location | Present | Value |
|---|---|---|
| `kai-app/gradle.properties` | **NO** | File contains only Gradle JVM/Android flags. No `OPENAI_API_KEY` line. |
| `kai-app/local.properties` | **NO** | Contains only `sdk.dir=/home/codespace/android-sdk` |
| `local.properties` (root) | **NO** | Same — `sdk.dir` only |
| Environment variable `OPENAI_API_KEY` | **YES** | `sk-proj-vIJCx3RrMIfbswN2u6JLmr0_kOa-...` (full key present) |

#### How it is loaded (build.gradle.kts `app/build.gradle.kts` L27–33)

```kotlin
val openAiKey =
    (project.findProperty("OPENAI_API_KEY") as String?)
        ?.trim()
        .orEmpty()
        .ifBlank { System.getenv("OPENAI_API_KEY")?.trim().orEmpty() }

buildConfigField("String", "OPENAI_API_KEY", "\"$openAiKey\"")
```

**Resolution order**:
1. Gradle property `OPENAI_API_KEY` (from `gradle.properties` or `-POPENAI_API_KEY=...` CLI flag) — **not present in gradle.properties**
2. Environment variable `OPENAI_API_KEY` — **present and non-blank**

At build time in this Codespaces environment, the key resolves from env var → baked into `BuildConfig.OPENAI_API_KEY` at compile time.

#### How it is read at runtime

- `OpenAIClient.kt` L228: `val key = BuildConfig.OPENAI_API_KEY.trim()`
- `OpenAIClient.kt` L285: same (streaming path)
- `KaiVoice.kt`: `val key = BuildConfig.OPENAI_API_KEY.trim()`

Empty-key guard in `OpenAIClient.ask()` (L229–231):
```kotlin
if (key.isBlank()) {
    return "OpenAI API key is missing. Put it in gradle.properties as OPENAI_API_KEY=..."
}
```
Empty-key guard in `OpenAIClient.askStream()` (L286–289):
```kotlin
if (key.isBlank()) {
    onError("OpenAI API key is missing.")
    return
}
```

**No null crash risk.** Empty key is handled gracefully — returns user-visible error, not exception.

#### Status: FOUND via environment variable — wiring correct

---

### 2. Supabase URL

#### Where it is defined

| Location | Present | Value |
|---|---|---|
| `kai-app/gradle.properties` | **NO** | Not present |
| Any `local.properties` | **NO** | Not present |
| Environment variable `SUPABASE_URL` | **YES** | `https://nolteoyayqvqaihunczz.supabase.co` |

#### How it is loaded (`app/build.gradle.kts` L35–39)

```kotlin
val supabaseUrl =
    (project.findProperty("SUPABASE_URL") as String?)
        ?.trim()
        .orEmpty()
        .ifBlank { System.getenv("SUPABASE_URL")?.trim().orEmpty() }

buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
```

Same two-step resolution as OpenAI key.

#### How it is read at runtime (`SupabaseClientProvider.kt` L19)

```kotlin
private val baseUrl: String = BuildConfig.SUPABASE_URL.trim().removeSuffix("/")
```

Guard in `isConfigured()` (L32–34):
```kotlin
fun isConfigured(): Boolean {
    return baseUrl.startsWith("http") && anonKey.isNotBlank()
}
```

If `SUPABASE_URL` is empty, `baseUrl` fails the `startsWith("http")` check → all Supabase calls return `false` / empty `JSONArray()` **silently**. No exception, no log.

#### Status: FOUND via environment variable — wiring correct

---

### 3. Supabase Anon Key

#### Where it is defined

| Location | Present | Value |
|---|---|---|
| `kai-app/gradle.properties` | **NO** | Not present |
| Any `local.properties` | **NO** | Not present |
| Environment variable `SUPABASE_ANON_KEY` | **YES** | `sb_publishable_-jwkPoqvCc9u91upGow51w_ks9aqy4t` |

#### How it is loaded (`app/build.gradle.kts` L41–45)

```kotlin
val supabaseAnonKey =
    (project.findProperty("SUPABASE_ANON_KEY") as String?)
        ?.trim()
        .orEmpty()
        .ifBlank { System.getenv("SUPABASE_ANON_KEY")?.trim().orEmpty() }

buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
```

#### How it is read at runtime (`SupabaseClientProvider.kt` L20)

```kotlin
private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY.trim()
```

Used in `baseRequest()` (L92–93):
```kotlin
.addHeader("apikey", anonKey)
.addHeader("Authorization", "Bearer $anonKey")
```

**Key format note**: The value `sb_publishable_-jwkPoqvCc9u91upGow51w_ks9aqy4t` uses Supabase's newer publishable key format (prefix `sb_publishable_`) rather than the classic long JWT anon key. This format is valid for Supabase REST API use. The `isConfigured()` check only requires the key to be non-blank — it does not validate format — so this will pass.

#### Status: FOUND via environment variable — wiring correct

---

### 4. Wiring Validation — Does Runtime Actually Receive the Keys?

```
Environment variable (Codespaces secret)
  → System.getenv("OPENAI_API_KEY") read at Gradle build time
  → buildConfigField("String", "OPENAI_API_KEY", "\"$value\"")
  → BuildConfig.OPENAI_API_KEY (compile-time constant, baked into APK)
  → OpenAIClient.ask() / askStream() / KaiVoice.kt at runtime
```

**The key is baked into the APK at compile time, not loaded dynamically at runtime.**

This means:
- If the app is built with the env var present → key is in BuildConfig → app works.
- If the app is built without the env var → key is `""` baked into BuildConfig → all API calls return the missing-key message.
- The APK does not re-read environment variables at runtime. Whatever was in `BuildConfig` when compiled is permanent until the next build.

The wiring chain is complete and unbroken for all 3 keys.

---

### 5. Additional Observations

#### KaiModelRouter package anomaly

- **File path**: `kai-app/app/src/main/java/com/example/reply/ui/KaiModelRouter.kt`
- **Package declaration**: `package com.example.reply.ai`
- **Imported as**: `import com.example.reply.ai.KaiModelRouter` in `OpenAIClient.kt` and `KaiVoice.kt`

The file is physically in the `ui/` directory but declares package `com.example.reply.ai`. Kotlin does not require package paths to match directory structure, so the build succeeds and imports resolve correctly. **Not a runtime risk.** Confusing but harmless.

#### No placeholder values found

No `"YOUR_API_KEY"`, `"REPLACE_ME"`, or empty-string placeholder values found in any source file or properties file. Keys are either genuinely present via env vars or absent (which is handled gracefully).

#### No .env file, no .devcontainer

- No `.env` file found anywhere in the workspace
- No `.devcontainer/` directory found (no Codespaces devcontainer config present)
- Keys arrive purely as GitHub Codespaces secrets exposed as environment variables

#### buildFeatures { buildConfig = true }

Confirmed in `app/build.gradle.kts` (L101–103): `buildConfig = true` is explicitly set. BuildConfig generation is active. The fields `OPENAI_API_KEY`, `SUPABASE_URL`, `SUPABASE_ANON_KEY` will be generated into `BuildConfig.java`.

---

### 6. Risks

| Risk | Severity | Detail |
|---|---|---|
| Build without env vars | HIGH | If a CI/CD build runs without the 3 secrets set, all API calls silently degrade: OpenAI returns user-visible error string; Supabase returns false/empty silently. App opens but is non-functional. |
| Supabase failure is silent | MEDIUM | `isConfigured()` returns false → all Supabase calls return false / JSONArray() with no log, no user message. Agent state persistence (KaiAgentStateRepository) silently stops. User will not know. |
| Key baked into APK | MEDIUM | The OpenAI key is embedded as a plaintext string in the compiled APK. Anyone who decompiles the APK (e.g. via `apktool`) can extract it. Standard risk for mobile apps with embedded secrets, but worth noting. |
| KaiVoice TTS uses gpt-4o-audio-preview | LOW | MODEL_TTS = `"gpt-4o-audio-preview"` — this model may not be available on all OpenAI accounts or tiers. If unavailable, TTS calls will fail with OpenAI error 404. The OpenAIClient returns the error as a string, not an exception — non-crashing but non-functional. |
| KaiAgentController uses Supabase persistence | LOW | `KaiAgentStateRepository.upsertState()` is wrapped in `runCatching {}` — Supabase failure is swallowed. If Supabase is not configured, agent state telemetry silently dies. Not a crash risk. |
| Variable rename detection | SAFE | All 3 BuildConfig field names (`OPENAI_API_KEY`, `SUPABASE_URL`, `SUPABASE_ANON_KEY`) match exactly between `build.gradle.kts` (definition) and all runtime consumers (OpenAIClient.kt, KaiVoice.kt, SupabaseClientProvider.kt). No mismatch found. |

---

### 7. Verdict

| Component | Status |
|---|---|
| OpenAI API Key | **SAFE** — Present via env var, wiring correct, empty-key guard in place |
| Supabase URL | **SAFE** — Present via env var, wiring correct, isConfigured() guard in place |
| Supabase Anon Key | **SAFE** — Present via env var, wiring correct |
| BuildConfig generation | **SAFE** — `buildConfig = true`, all 3 fields declared, no renamed/removed fields |
| gradle.properties | **NOTE** — Contains no API keys. Keys depend entirely on environment variables. Builds in environments without these secrets will produce a non-functional APK silently. |
| KaiModelRouter | **SAFE** — Valid model names (`gpt-4o`, `gpt-4o-mini`, `gpt-4o-audio-preview`). Package/path mismatch is cosmetic only. |
| Overall | **SAFE for current Codespaces environment. NEEDS FIX for CI/CD reproducibility** — no gradle.properties fallback values and no documentation of required secrets for fresh builds. |


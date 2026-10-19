# AI Chat Hub

A local-first Android app for discovering, downloading and chatting with **uncensored (low-refusal) on-device AI models**.
Chats and AI inference stay on your device. Network access is used only for model/catalog downloads and related update checks.

Built with **Jetpack Compose** and a real **llama.cpp** runtime (llama-kotlin-android) that loads **GGUF** models directly.

---

## Features

- **Model Store** — a curated catalog of verified GGUF models
  with real compatibility analysis for your specific device (RAM, storage, ABI) and
  one-tap **Download** on each card.
- **Production download engine** — parallel segmented downloads (up to 8 segments over HTTP Range),
  pause / resume / cancel, resume across app restarts, SHA-256 checksum verification before
  install, storage preflight and real-time stats (progress %, current/avg speed, ETA, segments,
  network type).
- **On-device chat** — real local inference with llama.cpp, plus in-app model switching
  between all installed models.
- **My Models** — manage installed models, delete and unload.
- **Playground** — test models with custom prompts and parameters.
- **Benchmark & Compare** — measure real generation speed and A/B test models.
- **Device analysis** — real RAM / storage / CPU data, with a conservative
  memory budget so the OS is never starved.
- **Conversation history** — saved locally in Room.
- **Local-first privacy** — no accounts, no cloud, no telemetry. Chats and
  on-device AI inference stay on your device. Network access is used only for
  model catalog updates and model file downloads.

## Model Catalog

All models are **real** GGUF artifacts hosted on Hugging Face, verified to resolve over HTTPS.
Each entry carries the exact file size and a SHA-256 checksum so downloads are verified before
install. Model binaries are **never bundled** in the APK — they are downloaded on demand.

### Recommended Models

| Model | Parameters | Quantization | File Size | License | Source |
|-------|-----------|--------------|-----------|---------|--------|
| Qwen3 4B Uncensored | 4B | Q4_K_M | ~2.3 GB | Apache 2.0 | [mradermacher/Qwen3-4B-abliterated-GGUF](https://huggingface.co/mradermacher/Qwen3-4B-abliterated-GGUF) |
| Gemma 4 E4B Uncensored | E4B | Q4_K_M | ~5.0 GB | Gemma Terms of Use | [mradermacher/gemma-4-E4B-it-ultra-uncensored-heretic-i1-GGUF](https://huggingface.co/mradermacher/gemma-4-E4B-it-ultra-uncensored-heretic-i1-GGUF) |
| Dolphin 3.0 Cyber 8B | 8B | Q4_K_M | ~4.6 GB | Llama 3.1 License | [RavichandranJ/Dolphin3-Cyber-8B-GGUF](https://huggingface.co/RavichandranJ/Dolphin3-Cyber-8B-GGUF) |
| Dolphin 2.9.4 Llama 3.1 8B | 8B | Q4_K_M | ~4.6 GB | Llama 3.1 License | [bartowski/dolphin-2.9.4-llama3.1-8b-GGUF](https://huggingface.co/bartowski/dolphin-2.9.4-llama3.1-8b-GGUF) |
| Dolphin 2.8 Mistral 7B | 7B | Q4_K_M | ~4.1 GB | Apache 2.0 | [lmstudio-community/dolphin-2.8-mistral-7b-v02-GGUF](https://huggingface.co/lmstudio-community/dolphin-2.8-mistral-7b-v02-GGUF) |

### Lightweight Models

| Model | Parameters | Quantization | File Size | License | Source |
|-------|-----------|--------------|-----------|---------|--------|
| SmolLM2 135M Instruct | 135M | Q4_K_M | ~100 MB | Apache 2.0 | [bartowski/SmolLM2-135M-Instruct-GGUF](https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF) |
| SmolLM2 360M Instruct | 360M | Q8_0 | ~368 MB | Apache 2.0 | [HuggingFaceTB/SmolLM2-360M-Instruct-GGUF](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF) |
| MobileLLM 125M | 125M | Q4_K_M | ~101 MB | CC-BY-NC 4.0 | [pjh64/MobileLLM-125M-GGUF](https://huggingface.co/pjh64/MobileLLM-125M-GGUF) |
| MobileLLM 350M | 350M | Q4_K_M | ~260 MB | CC-BY-NC 4.0 | [pjh64/MobileLLM-350M-GGUF](https://huggingface.co/pjh64/MobileLLM-350M-GGUF) |
| MobileLLM 600M | 600M | Q4_K_M | ~437 MB | CC-BY-NC 4.0 | [RichardErkhov/facebook_-_MobileLLM-600M-gguf](https://huggingface.co/RichardErkhov/facebook_-_MobileLLM-600M-gguf) |
| Qwen2.5 0.5B Instruct | 0.5B | Q4_K_M | ~468 MB | Apache 2.0 | [Qwen/Qwen2.5-0.5B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF) |
| Qwen2.5-Coder 0.5B Instruct | 0.5B | Q4_K_M | ~468 MB | Apache 2.0 | [Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF](https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF) |
| OpenELM 270M | 270M | Q4_K_M | ~167 MB | Apple Sample Code | [RichardErkhov/apple_-_OpenELM-270M-gguf](https://huggingface.co/RichardErkhov/apple_-_OpenELM-270M-gguf) |
| OpenELM 450M | 450M | Q4_K_M | ~276 MB | Apple Sample Code | [RichardErkhov/apple_-_OpenELM-450M-gguf](https://huggingface.co/RichardErkhov/apple_-_OpenELM-450M-gguf) |
| TinyLlama 1.1B Chat | 1.1B | Q4_K_M | ~637 MB | Apache 2.0 | [TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF](https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF) |

Respect the license terms of each model. Gemma, Llama, and MobileLLM have usage requirements from their owners.

## Tech Stack

- **Kotlin 2.4.20** + **Jetpack Compose** (Material 3, dark theme)
- **llama-kotlin-android 0.1.7** (`org.codeshipping`) — real llama.cpp on-device inference for GGUF
- **Room 2.7.1** — local persistence (models, conversations, messages)
- **OkHttp 4.12.0** — parallel segmented, resumable, checksum-verified model downloads
- **DataStore** — user settings
- **Navigation Compose 2.9.0** — app navigation
- **Hilt 2.56.2** — dependency injection
- **WorkManager 2.10.1** — background catalog sync

## Security

- **Ed25519 signature verification** for remote catalog manifests with key rotation support
- **Manifest version rollback protection**
- **HTTPS-only** downloads with redirect validation and SSRF protection
- **SHA-256 checksum** verification for all model files
- **GGUF header validation** before installation
- **Gitleaks** CI secret scanning (replaces naive grep-based detection)
- **Signing secrets** loaded from environment variables (never committed to repository)

## Requirements

- **Device**: Android 8.0+ (API 26), **arm64-v8a** (llama-kotlin ships native libs for arm64 only)
- **Build**: JDK 17, Gradle 8.12+ (wrapper included), Android SDK 36, Kotlin 2.4.20

## Build

```bash
./gradlew assembleDebug
```

For release builds, set the following environment variables:
```bash
export KEYSTORE_FILE_PATH=path/to/keystore
export KEYSTORE_PASSWORD=your_password
export KEY_ALIAS=your_alias
export KEY_PASSWORD=your_key_password
./gradlew assembleRelease
```

## CI/CD

GitHub Actions workflows are included:
- **CI** (`.github/workflows/ci.yml`) — runs on every push/PR: lint, tests, build, security scan (gitleaks)
- **Release** (`.github/workflows/release.yml`) — builds signed release APK on tag push

Signing secrets are stored in GitHub Secrets and injected as environment variables during the build.

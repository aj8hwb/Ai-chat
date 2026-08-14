# AI Chat Hub

A local-first Android app for discovering, downloading and chatting with **on-device AI models**.
Everything runs on your phone — your data never leaves your device.

Built with **Jetpack Compose** and the **MediaPipe LLM Inference API** (LiteRT-LM runtime).

---

## Features

- **Model Store** — browse a curated catalog of real, verified local AI models with
  compatibility analysis for your specific device (RAM, storage, ABI).
- **On-device chat** — stream responses token-by-token, entirely offline.
- **Download manager** — HTTPS downloads with pause / resume / cancel and progress.
- **My Models** — manage installed models, delete and unload.
- **Playground** — test models with custom prompts and parameters.
- **Benchmark & Compare** — measure real generation speed and A/B test models.
- **Device analysis** — real RAM / storage / CPU data, with a conservative
  memory budget so the OS is never starved.
- **Conversation history** — saved locally in Room.
- **Local-first privacy** — no accounts, no cloud, no telemetry.

## Model Catalog

All models are **real** and verified to serve a MediaPipe/LiteRT compatible artifact
(`.task` or `.litertlm`) over HTTPS. Model binaries are **never bundled** in the APK —
they are downloaded on demand to device storage.

| Model | Parameters | Format | File Size | License | Source |
|-------|-----------|--------|-----------|---------|--------|
| Qwen3 0.6B | 0.6B | `.litertlm` | ~586 MB | Apache 2.0 | [litert-community/Qwen3-0.6B](https://huggingface.co/litert-community/Qwen3-0.6B) |
| SmolLM 135M | 135M | `.task` | ~159 MB | Apache 2.0 | [litert-community/SmolLM-135M-Instruct](https://huggingface.co/litert-community/SmolLM-135M-Instruct) |
| Llama 3.2 1B | 1B | `.litertlm` | ~920 MB | Llama 3.2 Community License | [litert-community/Llama-3.2-1B](https://huggingface.co/litert-community/Llama-3.2-1B) |
| Gemma 2 2B | 2B | `.task` | ~2.5 GB | Gemma Terms of Use | [litert-community/Gemma2-2B-IT](https://huggingface.co/litert-community/Gemma2-2B-IT) |
| Phi-4 Mini | 3.8B | `.task` | ~3.7 GB | MIT License | [litert-community/Phi-4-mini-instruct](https://huggingface.co/litert-community/Phi-4-mini-instruct) |

Respect the license terms of each model. Gemma and Llama have usage requirements
from their owners.

## Tech Stack

- **Kotlin 2.0.20** + **Jetpack Compose** (Material 3, dark theme)
- **MediaPipe tasks-genai 0.10.27** — on-device LLM inference
- **Room** — local persistence (models, conversations, messages)
- **OkHttp** — resumable model downloads
- **DataStore** — user settings
- **Navigation Compose** — app navigation

## Requirements

- **Device**: Android 8.0+ (API 26), arm64-v8a or armeabi-v7a
- **Build**: JDK 17, Gradle 8.9 (wrapper included), Android SDK 35

## Build

```bash
./gradlew assembleRelease
```

The release APK is produced at `app/build/outputs/apk/release/`. It is signed with
the release keystore (`aichathub-release.keystore`) via `keystore.properties`.

A GitHub Actions workflow (`.github/workflows/build-apk.yml`) builds the signed
release APK automatically on every push and uploads it as a build artifact.

## Architecture

```
com.aichathub.app
├── ui/          Compose screens, theme, components, navigation
├── chat/        InferenceRuntime interface + MediaPipe engine + ChatCoordinator
├── device/      DeviceProfile, memory budget, compatibility engine
├── data/        ModelRepository, SettingsRepository, Room database
├── download/    Resumable download manager
└── di/          Manual dependency container
```

The app depends only on the `InferenceRuntime` interface — the concrete MediaPipe
engine can be swapped without touching the UI.

## Privacy

Everything is local: models, chats, settings and download metadata all live on your
device. The only network access is the direct download of model files from their
official Hugging Face sources over HTTPS.

## License

This app is provided as a reference implementation. Model files are subject to
their respective licenses (see catalog above).
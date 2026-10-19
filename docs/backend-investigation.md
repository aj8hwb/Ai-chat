# Backend / Acceleration Investigation (AI Chat Hub)

Evidence-based investigation into GPU offload (spec §5) and a LiteRT-LM/NPU
alternative backend (spec §7). Statuses are deliberately conservative: nothing
here claims acceleration that has not been executed on a device.

## 1. What the current dependency actually provides

Dependency: `org.codeshipping:llama-kotlin-android:0.1.7` (prebuilt AAR, no
`externalNativeBuild` in the project — we cannot rebuild the native layer
without forking/packaging our own native build).

### 1.1 Kotlin API surface (inspected with javap from the AAR's classes.jar)

`org.codeshipping.llamakotlin` contains only:

- `LlamaConfig` — fields: `contextSize, batchSize, threads, threadsBatch,
  temperature, topP, topK, repeatPenalty, frequencyPenalty, presencePenalty,
  maxTokens, stopSequences, useMmap, useMlock, gpuLayers, seed`.
  → `gpuLayers` EXISTS in the config type, but see §1.3: the packaged native
  build has no GPU backend to dispatch those layers to.
- `LlamaModel` — `load(file, config)`, `generateStream`, `generate`,
  `cancelGeneration`, `applyChatTemplate`, `getChatTemplate`, `getVersion`,
  `close`. There is NO metadata API (no way to read architecture/KV-cache
  quantities from a loaded model).
- `LlamaNative` — JNI bridge; companion only.

### 1.2 JNI surface (strings on `jni/arm64-v8a/libllama-android.so`)

`nativeGetVersion, nativeCreateContext, nativeDestroyContext,
nativeLoadModel, nativeUnloadModel, nativeIsModelLoaded, nativeGenerate,
nativeGenerateStream, nativeCancelGeneration, nativeIsGenerating,
nativeApplyChatTemplate, nativeGetChatTemplate, nativeGetLastError`

No metadata/tokenizer/KV-configuration functions are exposed.

### 1.3 GPU backend evidence (negative conclusions, verified)

Strings/greps on the packaged `libllama-android.so` (arm64-v8a):

- NO `ggml_backend_vulkan*` symbols, NO "vulkan"/"opencl"/"adreno" strings.
- NO backend-registry symbols for anything besides the CPU backend.
- Only CPU optimization paths are present, e.g. `ggml_cpu_has_*` probes and
  REPACK (`CPU_REPACK`) KleidiAI-style repack hooks — CPU-only optimizations,
  not GPU.

**Conclusion (GPU blocker, exact):** the prebuilt native library was compiled
without any GPU ggml backend. `LlamaConfig.gpuLayers > 0` cannot work with
this AAR — there is no backend to receive the layers. This is why the app
pins `gpuLayers = 0` and advertises only `BackendType.CPU`
(`LlamaCppRuntimeProvider.supportedBackends`, `BackendSelector`).

### 1.4 Candidate paths to real GPU offload (NONE verified yet)

1. **Build llama.cpp with the Vulkan backend ourselves** (custom
   `externalNativeBuild` or a forked prebuilt AAR). Requires: a pinned
   llama.cpp revision with stable JNI bindings, CMake toolchain for Android
   arm64-v8a, Vulkan headers, shader assets packaged in the AAR, APK-size
   review (~ several MB extra native code), and device verification on
   Adreno/Mali. NOT DONE — cannot be validated without native builds and
   physical devices.
2. **Upgrade to a newer binding release** that ships a Vulkan-enabled AAR.
   Not available at the time of writing (0.1.7 is the newest found).
3. **OpenCL (Adreno ML) backend**: llama.cpp's OpenCL Android path is
   experimental; no evidence this dependency supports it. Do not pursue
   without the same verification bar as Vulkan.

Chosen policy: keep `gpuLayers = 0` and CPU-only claims until a build with a
GPU backend exists AND a device run proves offload (initialization success,
actual layers offloaded, no crash/fallback).

## 2. LiteRT-LM / NPU evaluation (spec §7)

- The app's model workflow is GGUF/llama.cpp end to end (model catalog,
  downloads, chat template, benchmark history are GGUF-specific).
- LiteRT-LM consumes `.task`/`.litertlm` artifacts produced by a conversion
  pipeline (`ai-edge`/Gemma-oriented), NOT arbitrary GGUF files. Running the
  project's current models through it would require a conversion pipeline
  plus per-model compatibility verification that cannot be done statically.
- Device-side accelerator availability (HTP/NPU delegates on Snapdragon/
  Genie/Qualcomm QNN, Coral, etc.) is device- and vendor-specific; no verified
  path exists for the project's target devices.
- **Decision:** NOT IMPLEMENTED — no verified model path or device support
  path. Keep the llama.cpp backend. Re-evaluate only if:
  (a) a target model has a proven LiteRT-LM artifact,
  (b) a device with a supported delegate is available for real execution.

## 3. KV-cache quantization assumption (documented limitation)

GGUF metadata does not carry the runtime's KV-cache type (`type_k/type_v`),
and the binding exposes no per-context cache-type configuration. The
estimator therefore assumes f16 (2 bytes/element) for BOTH K and V — the
llama.cpp default. This is a documented assumption, not a measured fact
(see `GgufMetadataReader`'s KDoc). If the binding ever exposes a cache-type
config, the assumption must be replaced by the real setting.

package com.aichathub.app.data.model

import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelFormat

/**
 * Built-in static model catalog.
 *
 * MVP policy: no cloud backend. The catalog is bundled with the app and each
 * model points to an official / clearly identified source that hosts a
 * MediaPipe/LiteRT compatible artifact (`.task` or `.litertlm`).
 *
 * All download URLs were verified to resolve and serve the model file directly
 * over HTTPS with a known Content-Length (enabling resume support).
 *
 * IMPORTANT: model binary files are NEVER bundled into the APK. They are
 * downloaded on demand from the official source to device storage.
 */
object LocalModelCatalog {

    private const val HF = "https://huggingface.co"
    private const val RESOLVE = "$HF/{repo}/resolve/main/{file}?download=true"

    val models: List<CatalogModel> = listOf(
        // ---------------------------------------------------------------
        // 1. Qwen3 0.6B — Apache 2.0 — lightweight entry-level
        // ---------------------------------------------------------------
        CatalogModel(
            id = "qwen3-0.6b",
            name = "Qwen3 0.6B",
            provider = "Alibaba / Qwen",
            description =
            "A compact, efficient instruction-tuned model from the Qwen3 family. " +
                "Great all-round balance of speed and quality, ideal for 4-6 GB devices.",
            parameters = "0.6B",
            category = "General Chat",
            format = ModelFormat.LITERTLM,
            quantization = "Q4",
            fileSizeBytes = 614_236_160,
            estimatedMemoryBytes = 1_600_000_000,
            contextLength = 32768,
            license = "Apache License 2.0",
            licenseType = "Open source",
            officialRepositoryUrl = "$HF/litert-community/Qwen3-0.6B",
            downloadUrl = RESOLVE
                .replace("{repo}", "litert-community/Qwen3-0.6B")
                .replace("{file}", "Qwen3-0.6B.litertlm"),
            fileName = "Qwen3-0.6B.litertlm",
            runtime = "LiteRT-LM",
            sourceNote = "Pre-converted by the LiteRT community for on-device use.",
            capabilities = listOf("chat", "multilingual", "lightweight"),
            modelRank = 1
        ),

        // ---------------------------------------------------------------
        // 2. SmolLM-135M — Apache 2.0 — ultra-light / testing
        // ---------------------------------------------------------------
        CatalogModel(
            id = "smollm-135m",
            name = "SmolLM 135M",
            provider = "Hugging Face",
            description =
            "An extremely lightweight instruction-tuned model designed for the lowest " +
                "resource devices and quick testing.",
            parameters = "135M",
            category = "Lightweight",
            format = ModelFormat.TASK,
            quantization = "Q8",
            fileSizeBytes = 166_754_726,
            estimatedMemoryBytes = 500_000_000,
            contextLength = 2048,
            license = "Apache License 2.0",
            licenseType = "Open source",
            officialRepositoryUrl = "$HF/litert-community/SmolLM-135M-Instruct",
            downloadUrl = RESOLVE
                .replace("{repo}", "litert-community/SmolLM-135M-Instruct")
                .replace("{file}", "SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task"),
            fileName = "SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
            runtime = "LiteRT-LM",
            sourceNote = "Pre-converted by the LiteRT community for on-device use.",
            capabilities = listOf("chat", "lightweight", "testing"),
            modelRank = 2
        ),

        // ---------------------------------------------------------------
        // 3. Llama 3.2 1B — Llama 3.2 license — general assistant
        // ---------------------------------------------------------------
        CatalogModel(
            id = "llama-3.2-1b",
            name = "Llama 3.2 1B",
            provider = "Meta",
            description =
            "Meta's instruction-tuned 1B model for conversational and local " +
                "applications. Subject to Meta's Llama license.",
            parameters = "1B",
            category = "General Chat",
            format = ModelFormat.LITERTLM,
            quantization = "Q4",
            fileSizeBytes = 963_903_488,
            estimatedMemoryBytes = 2_200_000_000,
            contextLength = 4096,
            license = "Llama 3.2 Community License",
            licenseType = "Open weights (restricted)",
            officialRepositoryUrl = "$HF/litert-community/Llama-3.2-1B",
            downloadUrl = RESOLVE
                .replace("{repo}", "litert-community/Llama-3.2-1B")
                .replace("{file}", "llama3_2_1b_mixed_int4_gpu.litertlm"),
            fileName = "llama3_2_1b_mixed_int4_gpu.litertlm",
            runtime = "LiteRT-LM",
            sourceNote = "Pre-converted by the LiteRT community. Uses Meta's license terms.",
            capabilities = listOf("chat", "general"),
            modelRank = 3
        ),

        // ---------------------------------------------------------------
        // 4. Gemma 2 2B — Gemma license — medium lightweight
        // ---------------------------------------------------------------
        CatalogModel(
            id = "gemma2-2b",
            name = "Gemma 2 2B",
            provider = "Google",
            description =
            "Google's 2B Gemma variant with strong reasoning for its size. " +
                "Requires a 6-8 GB device for a comfortable experience.",
            parameters = "2B",
            category = "General Chat",
            format = ModelFormat.TASK,
            quantization = "Q8",
            fileSizeBytes = 2_713_274_466,
            estimatedMemoryBytes = 4_500_000_000,
            contextLength = 4096,
            license = "Gemma Terms of Use",
            licenseType = "Open weights",
            officialRepositoryUrl = "$HF/litert-community/Gemma2-2B-IT",
            downloadUrl = RESOLVE
                .replace("{repo}", "litert-community/Gemma2-2B-IT")
                .replace("{file}", "Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.task"),
            fileName = "Gemma2-2B-IT_multi-prefill-seq_q8_ekv1280.task",
            runtime = "LiteRT-LM",
            sourceNote = "Pre-converted by the LiteRT community. Uses Google's Gemma terms.",
            capabilities = listOf("chat", "reasoning", "multilingual"),
            modelRank = 4
        ),

        // ---------------------------------------------------------------
        // 5. Phi-4 Mini — MIT — higher-end
        // ---------------------------------------------------------------
        CatalogModel(
            id = "phi-4-mini",
            name = "Phi-4 Mini",
            provider = "Microsoft",
            description =
            "A capable Microsoft language model with an MIT license. " +
                "Recommended only for 8 GB+ devices due to its size.",
            parameters = "3.8B",
            category = "Reasoning",
            format = ModelFormat.TASK,
            quantization = "Q8",
            fileSizeBytes = 3_944_276_588,
            estimatedMemoryBytes = 6_000_000_000,
            contextLength = 4096,
            license = "MIT License",
            licenseType = "Open source",
            officialRepositoryUrl = "$HF/litert-community/Phi-4-mini-instruct",
            downloadUrl = RESOLVE
                .replace("{repo}", "litert-community/Phi-4-mini-instruct")
                .replace("{file}", "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task"),
            fileName = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task",
            runtime = "LiteRT-LM",
            sourceNote = "Pre-converted by the LiteRT community.",
            capabilities = listOf("chat", "reasoning", "coding"),
            modelRank = 5
        )
    )

    fun byId(id: String): CatalogModel? = models.firstOrNull { it.id == id }
}

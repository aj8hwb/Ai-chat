package com.aichathub.app.data.model

import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelFormat

/**
 * Built-in static model catalog.
 *
 * MVP policy: no cloud backend. The catalog is bundled with the app and each
 * model points to an official / clearly identified Hugging Face repository
 * that hosts a GGUF artifact (Q4_K_M quantization) compatible with the
 * bundled llama.cpp runtime (llama-android).
 *
 * All download URLs resolve over HTTPS to the real model files; every entry
 * carries the exact file size and the SHA-256 checksum of the artifact so the
 * download engine can verify integrity after transfer (no corrupted installs).
 *
 * IMPORTANT: model binary files are NEVER bundled into the APK. They are
 * downloaded on demand from the official source to device storage.
 */
object LocalModelCatalog {

    private const val HF = "https://huggingface.co"
    private const val RESOLVE = "$HF/{repo}/resolve/main/{file}?download=true"

    val models: List<CatalogModel> = listOf(
        // ---------------------------------------------------------------
        // 1. Qwen3 4B (abliterated) — low-refusal general chat, lightest
        // ---------------------------------------------------------------
        CatalogModel(
            id = "qwen3-4b-uncensored",
            name = "Qwen3 4B Uncensored",
            provider = "Hui Hui (abliterated)",
            description =
            "An abliterated (low-refusal) Qwen3 4B instruct model. The smallest of the " +
                "five models, making it the best first download for most phones while " +
                "still delivering strong multilingual chat and reasoning.",
            parameters = "4B",
            category = "Uncensored Chat",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            fileSizeBytes = 2_497_280_704,
            estimatedMemoryBytes = 4_600_000_000,
            contextLength = 8192,
            license = "Apache License 2.0",
            licenseType = "Open source",
            officialRepositoryUrl = "$HF/mradermacher/Qwen3-4B-abliterated-GGUF",
            downloadUrl = RESOLVE
                .replace("{repo}", "mradermacher/Qwen3-4B-abliterated-GGUF")
                .replace("{file}", "Qwen3-4B-abliterated.Q4_K_M.gguf"),
            fileName = "Qwen3-4B-abliterated.Q4_K_M.gguf",
            runtime = "llama.cpp",
            sourceNote = "Abliterated Qwen3 4B instruct, GGUF-quantized by mradermacher. Qwen3 is Apache 2.0.",
            capabilities = listOf("chat", "low-refusal", "multilingual", "lightweight"),
            modelRank = 1,
            checksumSha256 = "655eb731037f42b66ada10ecc36681e1f46450f7010b32978ca4a9359a5e1302"
        ),

        // ---------------------------------------------------------------
        // 2. Gemma 4 E4B (ultra uncensored) — strongest uncensored chat
        // ---------------------------------------------------------------
        CatalogModel(
            id = "gemma-4-e4b-uncensored",
            name = "Gemma 4 E4B Uncensored",
            provider = "Hui Hui (abliterated)",
            description =
            "Gemma 4 E4B 'ultra uncensored' instruct with very low refusal behaviour. " +
                "The most capable uncensored model in the store; needs a high-end phone.",
            parameters = "E4B",
            category = "Uncensored Chat",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            fileSizeBytes = 5_335_286_624,
            estimatedMemoryBytes = 8_500_000_000,
            contextLength = 8192,
            license = "Gemma Terms of Use",
            licenseType = "Open weights",
            officialRepositoryUrl = "$HF/mradermacher/gemma-4-E4B-it-ultra-uncensored-heretic-i1-GGUF",
            downloadUrl = RESOLVE
                .replace("{repo}", "mradermacher/gemma-4-E4B-it-ultra-uncensored-heretic-i1-GGUF")
                .replace("{file}", "gemma-4-E4B-it-ultra-uncensored-heretic.i1-Q4_K_M.gguf"),
            fileName = "gemma-4-E4B-it-ultra-uncensored-heretic.i1-Q4_K_M.gguf",
            runtime = "llama.cpp",
            sourceNote = "Abliterated Gemma 4 E4B, GGUF-quantized by mradermacher. Best with 12 GB+ RAM.",
            capabilities = listOf("chat", "low-refusal", "creative", "high-end"),
            modelRank = 2,
            checksumSha256 = "c92b92d3b000a177bba4daad26577fd8203a12e371d2f9acc2e385479bf51e23"
        ),

        // ---------------------------------------------------------------
        // 3. Dolphin 3.0 Cyber 8B — uncensored coding & security
        // ---------------------------------------------------------------
        CatalogModel(
            id = "dolphin-3-cyber-8b",
            name = "Dolphin 3.0 Cyber 8B",
            provider = "Cognitive Computations",
            description =
            "Dolphin 3.0 Llama 3.1 8B abliterated, tuned on uncensored general knowledge " +
                "with a strong bias towards coding, pentesting and cyber topics. " +
                "Low-refusal and highly technical.",
            parameters = "8B",
            category = "Coding & Security",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            fileSizeBytes = 4_920_745_984,
            estimatedMemoryBytes = 8_000_000_000,
            contextLength = 8192,
            license = "Llama 3.1 Community License",
            licenseType = "Open weights (restricted)",
            officialRepositoryUrl = "$HF/RavichandranJ/Dolphin3-Cyber-8B-GGUF",
            downloadUrl = RESOLVE
                .replace("{repo}", "RavichandranJ/Dolphin3-Cyber-8B-GGUF")
                .replace("{file}", "Dolphin3.0-Llama3.1-8B-abliterated.Q4_K_M.gguf"),
            fileName = "Dolphin3.0-Llama3.1-8B-abliterated.Q4_K_M.gguf",
            runtime = "llama.cpp",
            sourceNote = "Dolphin 3.0 abliterated GGUF. Ideal for code, security and low-refusal topics.",
            capabilities = listOf("chat", "coding", "cyber", "low-refusal"),
            modelRank = 3,
            checksumSha256 = "73da18db1557e19e8ec2d6c1e8ef08e182c735d72f3bd526f6940f4fec96c1cb"
        ),

        // ---------------------------------------------------------------
        // 4. Dolphin 2.9.4 Llama 3.1 8B — balanced uncensored assistant
        // ---------------------------------------------------------------
        CatalogModel(
            id = "dolphin-2.9.4-llama3.1-8b",
            name = "Dolphin 2.9.4 Llama 3.1 8B",
            provider = "Cognitive Computations",
            description =
            "The classic Dolphin 2.9.4 (Llama 3.1 8B) abliterated instruct — a reliable, " +
                "low-refusal general assistant with strong reasoning. A favourite for " +
                "long local chats on 8 GB+ phones.",
            parameters = "8B",
            category = "Uncensored Chat",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            fileSizeBytes = 4_920_746_784,
            estimatedMemoryBytes = 8_000_000_000,
            contextLength = 8192,
            license = "Llama 3.1 Community License",
            licenseType = "Open weights (restricted)",
            officialRepositoryUrl = "$HF/bartowski/dolphin-2.9.4-llama3.1-8b-GGUF",
            downloadUrl = RESOLVE
                .replace("{repo}", "bartowski/dolphin-2.9.4-llama3.1-8b-GGUF")
                .replace("{file}", "dolphin-2.9.4-llama3.1-8b-Q4_K_M.gguf"),
            fileName = "dolphin-2.9.4-llama3.1-8b-Q4_K_M.gguf",
            runtime = "llama.cpp",
            sourceNote = "Dolphin 2.9.4 abliterated, GGUF-quantized by bartowski. Uncensored Dolphin at its best.",
            capabilities = listOf("chat", "low-refusal", "reasoning"),
            modelRank = 4,
            checksumSha256 = "601e951c801c1d9140a89bf4f1acfc1ab34991e4ab18d9aaac8f09e3bab4001d"
        ),

        // ---------------------------------------------------------------
        // 5. Dolphin 2.8 Mistral 7B — uncensored, mid-range friendly
        // ---------------------------------------------------------------
        CatalogModel(
            id = "dolphin-2.8-mistral-7b",
            name = "Dolphin 2.8 Mistral 7B",
            provider = "Cognitive Computations",
            description =
            "Dolphin 2.8 Mistral 7B v02 — an uncensored, low-refusal 7B model built on " +
                "Mistral. Lighter than the 8B Dolphin models, it runs on phones with " +
                "less headroom while keeping uncensored behaviour.",
            parameters = "7B",
            category = "Uncensored Chat",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            fileSizeBytes = 4_368_450_688,
            estimatedMemoryBytes = 7_200_000_000,
            contextLength = 8192,
            license = "Apache License 2.0",
            licenseType = "Open source",
            officialRepositoryUrl = "$HF/lmstudio-community/dolphin-2.8-mistral-7b-v02-GGUF",
            downloadUrl = RESOLVE
                .replace("{repo}", "lmstudio-community/dolphin-2.8-mistral-7b-v02-GGUF")
                .replace("{file}", "dolphin-2.8-mistral-7b-v02-Q4_K_M.gguf"),
            fileName = "dolphin-2.8-mistral-7b-v02-Q4_K_M.gguf",
            runtime = "llama.cpp",
            sourceNote = "Dolphin 2.8 Mistral 7B v02, GGUF-quantized by the LM Studio community.",
            capabilities = listOf("chat", "low-refusal", "balanced"),
            modelRank = 5,
            checksumSha256 = "acf5681de049fe3c2486def0e744089b6d1894cf613700386c9d5c17a3a07412"
        )
    )

    fun byId(id: String): CatalogModel? = models.firstOrNull { it.id == id }
}

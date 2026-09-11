package com.aichathub.app.data.model

import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ChatTemplate
import com.aichathub.app.domain.model.ModelFormat

/**
 * Built-in static model catalog.
 *
 * MVP policy: no cloud backend. The catalog is bundled with the app and each
 * model points to an official / clearly identified Hugging Face repository
 * that hosts a GGUF artifact compatible with the bundled llama.cpp runtime
 * (llama-android).
 *
 * Every entry carries the exact file size and the SHA-256 checksum of the
 * artifact (read from the repository's LFS pointer) so the download engine can
 * verify integrity after transfer (no corrupted installs).
 *
 * IMPORTANT: model binary files are NEVER bundled into the APK. They are
 * downloaded on demand from the official source to device storage.
 */
object LocalModelCatalog {

    private const val HF = "https://huggingface.co"
    private const val RESOLVE = "$HF/{repo}/resolve/main/{file}?download=true"

    val models: List<CatalogModel> = listOf(
        // ---------------------------------------------------------------
        // 1. Qwen3 4B (abliterated) — low-refusal general chat
        // ---------------------------------------------------------------
        CatalogModel(
            id = "qwen3-4b-uncensored",
            name = "Qwen3 4B Uncensored",
            provider = "Hui Hui (abliterated)",
            description =
            "An abliterated (low-refusal) Qwen3 4B instruct model. The smallest of the " +
                "large uncensored models, making it the best first download for most phones while " +
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
            capabilities = listOf("chat", "low-refusal", "multilingual", "reasoning"),
            modelRank = 1,
            checksumSha256 = "655eb731037f42b66ada10ecc36681e1f46450f7010b32978ca4a9359a5e1302",
            purposeEmoji = "🧠",
            purposeTitle = "General Chat · Low-Refusal",
            bestFor = "Chat · Reasoning · Multilingual",
            primaryPurpose =
            "An uncensored general-purpose assistant. Handles open-ended chat, reasoning and " +
                "multilingual conversations with very low refusal behaviour.",
            strengths = listOf("Low-refusal", "Multilingual", "Good reasoning", "Apache 2.0"),
            limitations = listOf("Needs roughly 6–8 GB of RAM"),
            parameterCount = 4_000_000_000,
            chatTemplate = ChatTemplate.CHATML
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
            checksumSha256 = "c92b92d3b000a177bba4daad26577fd8203a12e371d2f9acc2e385479bf51e23",
            purposeEmoji = "🧠",
            purposeTitle = "High-Capability Uncensored Chat",
            bestFor = "Long-form chats · Creative writing · Low-refusal",
            primaryPurpose =
            "The highest-capability uncensored model in the store. Best for long, creative, " +
                "low-refusal conversations on high-end devices.",
            strengths = listOf("Very low refusal", "Creative", "Strong comprehension"),
            limitations = listOf("Needs 12 GB+ RAM", "Slow on mid-range phones"),
            parameterCount = 4_000_000_000,
            chatTemplate = ChatTemplate.GEMMA
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
            checksumSha256 = "73da18db1557e19e8ec2d6c1e8ef08e182c735d72f3bd526f6940f4fec96c1cb",
            purposeEmoji = "💻",
            purposeTitle = "Coding & Cyber Specialist",
            bestFor = "Code generation · Security topics · Technical Q&A",
            primaryPurpose =
            "Uncensored assistant strongly tuned toward coding, pentesting and cyber topics, " +
                "with low refusal behaviour.",
            strengths = listOf("Coding", "Cyber/security", "Low-refusal"),
            limitations = listOf("Needs 8 GB+ RAM"),
            parameterCount = 8_000_000_000,
            chatTemplate = ChatTemplate.LLAMA3
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
            checksumSha256 = "601e951c801c1d9140a89bf4f1acfc1ab34991e4ab18d9aaac8f09e3bab4001d",
            purposeEmoji = "🧠",
            purposeTitle = "Balanced Uncensored Assistant",
            bestFor = "General chat · Reasoning · Writing",
            primaryPurpose =
            "A classic low-refusal general assistant with strong reasoning — reliable for " +
                "long local conversations.",
            strengths = listOf("Reliable", "Reasoning", "Low-refusal"),
            limitations = listOf("Needs 8 GB+ RAM"),
            parameterCount = 8_000_000_000,
            chatTemplate = ChatTemplate.LLAMA3
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
            checksumSha256 = "acf5681de049fe3c2486def0e744089b6d1894cf613700386c9d5c17a3a07412",
            purposeEmoji = "🧠",
            purposeTitle = "Uncensored General Chat",
            bestFor = "Chat · Writing · Light reasoning",
            primaryPurpose =
            "An uncensored 7B assistant lighter than the 8B Dolphin models — good on phones " +
                "with moderate headroom.",
            strengths = listOf("Balanced", "Low-refusal", "Apache 2.0"),
            limitations = listOf("Needs ~7 GB of RAM"),
            parameterCount = 7_000_000_000,
            chatTemplate = ChatTemplate.CHATML
        ),

        // ---------------------------------------------------------------
        // 6. SmolLM2 135M Instruct — ultra lightweight basic chat
        // ---------------------------------------------------------------
        CatalogModel(
            id = "smollm2-135m-instruct",
            name = "SmolLM2 135M Instruct",
            provider = "Hugging Face TB",
            description =
            "A very small instruction-tuned model built for on-device use. Excellent for basic " +
                "chat, simple Q&A, rewriting and lightweight text generation on low-resource phones.",
            parameters = "135M",
            category = "Lightweight Models",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            fileSizeBytes = 105_454_432,
            estimatedMemoryBytes = 400_000_000,
            contextLength = 2048,
            license = "Apache License 2.0",
            licenseType = "Open source",
            officialRepositoryUrl = "$HF/bartowski/SmolLM2-135M-Instruct-GGUF",
            downloadUrl = RESOLVE
                .replace("{repo}", "bartowski/SmolLM2-135M-Instruct-GGUF")
                .replace("{file}", "SmolLM2-135M-Instruct-Q4_K_M.gguf"),
            fileName = "SmolLM2-135M-Instruct-Q4_K_M.gguf",
            runtime = "llama.cpp",
            sourceNote = "Official SmolLM2 135M instruct, GGUF-quantized by bartowski. The SmolLM2 family is designed for on-device use.",
            capabilities = listOf("chat", "lightweight", "on-device"),
            modelRank = 6,
            checksumSha256 = "2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d",
            purposeEmoji = "⚡",
            purposeTitle = "Ultra Lightweight · Basic Chat",
            bestFor = "Simple Q&A · Rewriting · Light text",
            primaryPurpose =
            "Extremely small instruction-tuned model for basic chat and simple text tasks on " +
                "very low-resource devices.",
            strengths = listOf("Tiny download (~105 MB)", "Runs on any device", "Apache 2.0"),
            limitations = listOf("Limited reasoning depth"),
            parameterCount = 135_000_000,
            chatTemplate = ChatTemplate.CHATML
        ),

        // ---------------------------------------------------------------
        // 7. SmolLM2 360M Instruct — lightweight general AI
        // ---------------------------------------------------------------
        CatalogModel(
            id = "smollm2-360m-instruct",
            name = "SmolLM2 360M Instruct",
            provider = "Hugging Face TB",
            description =
            "A 360M instruction-tuned SmolLM2 model with noticeably better reasoning, writing, " +
                "summarization and general chat than the 135M variant, still small enough for " +
                "older phones.",
            parameters = "360M",
            category = "Lightweight Models",
            format = ModelFormat.GGUF,
            quantization = "Q8_0",
            fileSizeBytes = 386_404_992,
            estimatedMemoryBytes = 900_000_000,
            contextLength = 2048,
            license = "Apache License 2.0",
            licenseType = "Open source",
            officialRepositoryUrl = "$HF/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF",
            downloadUrl = RESOLVE
                .replace("{repo}", "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF")
                .replace("{file}", "smollm2-360m-instruct-q8_0.gguf"),
            fileName = "smollm2-360m-instruct-q8_0.gguf",
            runtime = "llama.cpp",
            sourceNote = "Official SmolLM2 360M instruct in the official GGUF repo (Q8_0).",
            capabilities = listOf("chat", "writing", "summarization", "lightweight"),
            modelRank = 7,
            checksumSha256 = "48ab3034d0dd401fbc721eb1df3217902fee7dab9078992d66431f09b7750201",
            purposeEmoji = "⚡",
            purposeTitle = "Lightweight General AI",
            bestFor = "Chat · Summaries · Basic reasoning",
            primaryPurpose =
            "A lightweight general model for chat, summarization and writing tasks on " +
                "low-to-mid resource devices.",
            strengths = listOf("Better reasoning than 135M", "Runs on most devices", "Apache 2.0"),
            limitations = listOf("Small model — keep expectations modest"),
            parameterCount = 360_000_000,
            chatTemplate = ChatTemplate.CHATML
        ),

        // ---------------------------------------------------------------
        // 8. MobileLLM 125M — ultra lightweight mobile AI (Meta)
        // ---------------------------------------------------------------
        CatalogModel(
            id = "mobilellm-125m",
            name = "MobileLLM 125M",
            provider = "Meta",
            description =
            "A sub-billion-parameter model from Meta Research optimized specifically for " +
                "on-device mobile use (deep-and-thin transformer, GQA, shared embeddings). " +
                "Great for ultra-lightweight text generation.",
            parameters = "125M",
            category = "Lightweight Models",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            fileSizeBytes = 106_583_456,
            estimatedMemoryBytes = 400_000_000,
            contextLength = 2048,
            license = "CC-BY-NC 4.0",
            licenseType = "Non-commercial open",
            officialRepositoryUrl = "$HF/pjh64/MobileLLM-125M-GGUF",
            downloadUrl = RESOLVE
                .replace("{repo}", "pjh64/MobileLLM-125M-GGUF")
                .replace("{file}", "MobileLLM-143M-Q4_K_M.gguf"),
            fileName = "MobileLLM-143M-Q4_K_M.gguf",
            runtime = "llama.cpp",
            sourceNote = "Meta MobileLLM-125M (ICML 2024), GGUF-quantized. License: CC-BY-NC 4.0 — non-commercial use only.",
            capabilities = listOf("lightweight", "on-device", "mobile"),
            modelRank = 8,
            checksumSha256 = "95a54c3ef1ad5e81d5648c0a11d510c316bcddd385fdaa9512d35916995e4358",
            purposeEmoji = "⚡",
            purposeTitle = "Ultra Lightweight · Mobile AI",
            bestFor = "Lightweight text generation",
            primaryPurpose =
            "Meta's MobileLLM 125M — an extremely light model architected for on-device use " +
                "on very constrained phones.",
            strengths = listOf("Tiny (~106 MB)", "Mobile-first design", "Fast on CPU"),
            limitations = listOf("Non-commercial license", "Very basic capabilities"),
            parameterCount = 125_000_000,
            chatTemplate = ChatTemplate.GENERIC
        ),

        // ---------------------------------------------------------------
        // 9. MobileLLM 350M — lightweight mobile AI (Meta)
        // ---------------------------------------------------------------
        CatalogModel(
            id = "mobilellm-350m",
            name = "MobileLLM 350M",
            provider = "Meta",
            description =
            "The 350M member of Meta's MobileLLM family. Delivers stronger generation than " +
                "the 125M while remaining light enough for modest Android devices.",
            parameters = "350M",
            category = "Lightweight Models",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            fileSizeBytes = 273_169_888,
            estimatedMemoryBytes = 800_000_000,
            contextLength = 2048,
            license = "CC-BY-NC 4.0",
            licenseType = "Non-commercial open",
            officialRepositoryUrl = "$HF/pjh64/MobileLLM-350M-GGUF",
            downloadUrl = RESOLVE
                .replace("{repo}", "pjh64/MobileLLM-350M-GGUF")
                .replace("{file}", "MobileLLM-376M-Q4_K_M.gguf"),
            fileName = "MobileLLM-376M-Q4_K_M.gguf",
            runtime = "llama.cpp",
            sourceNote = "Meta MobileLLM-350M, GGUF-quantized. License: CC-BY-NC 4.0 — non-commercial use only.",
            capabilities = listOf("lightweight", "on-device", "mobile"),
            modelRank = 9,
            checksumSha256 = "a6ba398f80ffdd93f627e66c1bad217af6a7f0e142e667fa9befadab03e33870",
            purposeEmoji = "⚡",
            purposeTitle = "Lightweight Mobile AI",
            bestFor = "Light general-purpose generation",
            primaryPurpose =
            "Meta's MobileLLM 350M — lightweight general-purpose generation for modest devices.",
            strengths = listOf("Efficient architecture", "Runs on low-RAM phones"),
            limitations = listOf("Non-commercial license", "Basic capabilities"),
            parameterCount = 350_000_000,
            chatTemplate = ChatTemplate.GENERIC
        ),

        // ---------------------------------------------------------------
        // 10. MobileLLM 600M — lightweight general / reasoning (Meta)
        // ---------------------------------------------------------------
        CatalogModel(
            id = "mobilellm-600m",
            name = "MobileLLM 600M",
            provider = "Meta",
            description =
            "The 600M member of Meta's MobileLLM family — noticeably more capable for " +
                "general chat and basic reasoning than the smaller MobileLLM models, while " +
                "still targeting on-device use.",
            parameters = "600M",
            category = "Lightweight Models",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            fileSizeBytes = 458_785_312,
            estimatedMemoryBytes = 1_100_000_000,
            contextLength = 2048,
            license = "CC-BY-NC 4.0",
            licenseType = "Non-commercial open",
            officialRepositoryUrl = "$HF/RichardErkhov/facebook_-_MobileLLM-600M-gguf",
            downloadUrl = RESOLVE
                .replace("{repo}", "RichardErkhov/facebook_-_MobileLLM-600M-gguf")
                .replace("{file}", "MobileLLM-600M.Q4_K_M.gguf"),
            fileName = "MobileLLM-600M.Q4_K_M.gguf",
            runtime = "llama.cpp",
            sourceNote = "Meta MobileLLM-600M, GGUF-quantized. License: CC-BY-NC 4.0 — non-commercial use only.",
            capabilities = listOf("chat", "reasoning", "lightweight"),
            modelRank = 10,
            checksumSha256 = "3520ff2c8094dc28b070d0bae463ef2c2f8f45622db752a8d75fd348423dc468",
            purposeEmoji = "⚡",
            purposeTitle = "Lightweight General / Reasoning",
            bestFor = "General chat · Basic reasoning",
            primaryPurpose =
            "The largest MobileLLM — good lightweight general chat and basic reasoning " +
                "for on-device use.",
            strengths = listOf("More capable than 125M/350M", "On-device friendly"),
            limitations = listOf("Non-commercial license"),
            parameterCount = 600_000_000,
            chatTemplate = ChatTemplate.GENERIC
        ),

        // ---------------------------------------------------------------
        // 11. Qwen2.5 0.5B Instruct — general assistant
        // ---------------------------------------------------------------
        CatalogModel(
            id = "qwen2.5-0.5b-instruct",
            name = "Qwen2.5 0.5B Instruct",
            provider = "Qwen / Alibaba",
            description =
            "A 0.5B instruction-tuned model with real multilingual and light coding ability. " +
                "Great general assistant for chat, Q&A and structured output on modest phones.",
            parameters = "0.5B",
            category = "Lightweight Models",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            fileSizeBytes = 491_400_032,
            estimatedMemoryBytes = 1_200_000_000,
            contextLength = 8192,
            license = "Apache License 2.0",
            licenseType = "Open source",
            officialRepositoryUrl = "$HF/Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            downloadUrl = RESOLVE
                .replace("{repo}", "Qwen/Qwen2.5-0.5B-Instruct-GGUF")
                .replace("{file}", "qwen2.5-0.5b-instruct-q4_k_m.gguf"),
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            runtime = "llama.cpp",
            sourceNote = "Official Qwen2.5 0.5B Instruct GGUF (Q4_K_M). Apache 2.0.",
            capabilities = listOf("chat", "multilingual", "coding", "lightweight"),
            modelRank = 11,
            checksumSha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            purposeEmoji = "💬",
            purposeTitle = "General Assistant",
            bestFor = "Chat · Q&A · Multilingual · Light coding",
            primaryPurpose =
            "A compact general assistant with strong multilingual coverage and light " +
                "coding/math capability.",
            strengths = listOf("Multilingual", "Light coding & math", "Apache 2.0"),
            limitations = listOf("Small model — shallow reasoning"),
            parameterCount = 500_000_000,
            chatTemplate = ChatTemplate.CHATML
        ),

        // ---------------------------------------------------------------
        // 12. Qwen2.5-Coder 0.5B Instruct — coding specialist
        // ---------------------------------------------------------------
        CatalogModel(
            id = "qwen2.5-coder-0.5b-instruct",
            name = "Qwen2.5-Coder 0.5B Instruct",
            provider = "Qwen / Alibaba",
            description =
            "A 0.5B model specialized for code: generation, explanation, debugging and small " +
                "programming tasks. The lightest genuinely coding-focused model in the store.",
            parameters = "0.5B",
            category = "Coding & Security",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            fileSizeBytes = 491_400_064,
            estimatedMemoryBytes = 1_200_000_000,
            contextLength = 8192,
            license = "Apache License 2.0",
            licenseType = "Open source",
            officialRepositoryUrl = "$HF/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF",
            downloadUrl = RESOLVE
                .replace("{repo}", "Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF")
                .replace("{file}", "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf"),
            fileName = "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
            runtime = "llama.cpp",
            sourceNote = "Official Qwen2.5-Coder 0.5B Instruct GGUF (Q4_K_M). Apache 2.0. 💻 Best for coding.",
            capabilities = listOf("coding", "debugging", "explanation", "lightweight"),
            modelRank = 12,
            checksumSha256 = "1d9614638d18024d0fbb36575a15f1302a3adf044df10345688ec4f6e1c4ff32",
            purposeEmoji = "💻",
            purposeTitle = "Coding Specialist",
            bestFor = "Code generation · Debugging · Explanations",
            primaryPurpose =
            "A lightweight model focused on code — generation, explanation and debugging of " +
                "small programming tasks.",
            strengths = listOf("Code-focused", "Debugging", "Apache 2.0"),
            limitations = listOf("0.5B — best for short snippets"),
            parameterCount = 500_000_000,
            chatTemplate = ChatTemplate.CHATML
        ),

        // ---------------------------------------------------------------
        // 13. OpenELM 270M — lightweight / experimental (Apple)
        // ---------------------------------------------------------------
        CatalogModel(
            id = "openelm-270m",
            name = "OpenELM 270M",
            provider = "Apple",
            description =
            "Apple's efficient 270M language model (layer-wise scaled). It is a base/text-" +
                "completion model, NOT instruction-tuned — treat it as an experimental, " +
                "low-resource exploration model.",
            parameters = "270M",
            category = "Lightweight Models",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            fileSizeBytes = 175_399_584,
            estimatedMemoryBytes = 600_000_000,
            contextLength = 2048,
            license = "Apple Sample Code License",
            licenseType = "Open weights (sample license)",
            officialRepositoryUrl = "$HF/RichardErkhov/apple_-_OpenELM-270M-gguf",
            downloadUrl = RESOLVE
                .replace("{repo}", "RichardErkhov/apple_-_OpenELM-270M-gguf")
                .replace("{file}", "OpenELM-270M.Q4_K_M.gguf"),
            fileName = "OpenELM-270M.Q4_K_M.gguf",
            runtime = "llama.cpp",
            sourceNote = "Apple OpenELM-270M (base model), GGUF-quantized. Experimental — text completion, not chat-tuned.",
            capabilities = listOf("lightweight", "experimental", "text-completion"),
            modelRank = 13,
            checksumSha256 = "6bb72b05e63b2f1878acde5c2c98cba3e1835b3018e5c6ccdd1b2619d8f66b6a",
            purposeEmoji = "🧪",
            purposeTitle = "Lightweight / Experimental",
            bestFor = "Text completion · Experimentation",
            primaryPurpose =
            "A base (non-instruction) experimental model for lightweight text completion " +
                "and experimentation on low-resource devices.",
            strengths = listOf("Very light", "Efficient architecture"),
            limitations = listOf("Not chat-tuned", "Experimental"),
            parameterCount = 270_000_000,
            chatTemplate = ChatTemplate.GENERIC
        ),

        // ---------------------------------------------------------------
        // 14. OpenELM 450M — lightweight general (experimental, Apple)
        // ---------------------------------------------------------------
        CatalogModel(
            id = "openelm-450m",
            name = "OpenELM 450M",
            provider = "Apple",
            description =
            "The 450M member of Apple's OpenELM family — more capable lightweight text " +
                "generation than the 270M. Also a base model: experimental rather than " +
                "instruction-tuned.",
            parameters = "450M",
            category = "Lightweight Models",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            fileSizeBytes = 289_467_168,
            estimatedMemoryBytes = 900_000_000,
            contextLength = 2048,
            license = "Apple Sample Code License",
            licenseType = "Open weights (sample license)",
            officialRepositoryUrl = "$HF/RichardErkhov/apple_-_OpenELM-450M-gguf",
            downloadUrl = RESOLVE
                .replace("{repo}", "RichardErkhov/apple_-_OpenELM-450M-gguf")
                .replace("{file}", "OpenELM-450M.Q4_K_M.gguf"),
            fileName = "OpenELM-450M.Q4_K_M.gguf",
            runtime = "llama.cpp",
            sourceNote = "Apple OpenELM-450M (base model), GGUF-quantized. Experimental — text completion, not chat-tuned.",
            capabilities = listOf("lightweight", "experimental", "text-completion"),
            modelRank = 14,
            checksumSha256 = "7373acdac90c20891399646ba2bf42d5f03e850b265442f35faef73c6faf35ea",
            purposeEmoji = "🧪",
            purposeTitle = "Lightweight General (Experimental)",
            bestFor = "Text completion · Experimentation",
            primaryPurpose =
            "A more capable base experimental model for lightweight text generation and " +
                "experimentation.",
            strengths = listOf("More capable than 270M", "Efficient"),
            limitations = listOf("Not chat-tuned", "Experimental"),
            parameterCount = 450_000_000,
            chatTemplate = ChatTemplate.GENERIC
        ),

        // ---------------------------------------------------------------
        // 15. TinyLlama 1.1B Chat — lightweight conversational AI
        // ---------------------------------------------------------------
        CatalogModel(
            id = "tinyllama-1.1b-chat",
            name = "TinyLlama 1.1B Chat",
            provider = "TinyLlama Community",
            description =
            "A compact 1.1B conversational model built on Llama 2 architecture. Noticeably " +
                "more capable than the 125M–600M models but needs more memory — on 4 GB phones " +
                "it is NOT automatically recommended.",
            parameters = "1.1B",
            category = "Lightweight Models",
            format = ModelFormat.GGUF,
            quantization = "Q4_K_M",
            fileSizeBytes = 668_788_096,
            estimatedMemoryBytes = 1_800_000_000,
            contextLength = 2048,
            license = "Apache License 2.0",
            licenseType = "Open source",
            officialRepositoryUrl = "$HF/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF",
            downloadUrl = RESOLVE
                .replace("{repo}", "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF")
                .replace("{file}", "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf"),
            fileName = "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            runtime = "llama.cpp",
            sourceNote = "TinyLlama 1.1B Chat v1.0, GGUF-quantized. Apache 2.0. 🟡 Medium — requires more memory.",
            capabilities = listOf("chat", "conversation", "text-generation"),
            modelRank = 15,
            checksumSha256 = "9fecc3b3cd76bba89d504f29b616eedf7da85b96540e490ca5824d3f7d2776a0",
            purposeEmoji = "🟡",
            purposeTitle = "Lightweight General Chat",
            bestFor = "Conversation · Text generation",
            primaryPurpose =
            "A compact conversational model. Stronger than the smaller models but requires " +
                "more memory — medium tier.",
            strengths = listOf("Better conversations than small models", "Apache 2.0"),
            limitations = listOf("Needs ~1.8 GB RAM", "Not auto-recommended on 4 GB phones"),
            parameterCount = 1_100_000_000,
            chatTemplate = ChatTemplate.LLAMA2
        )
    )

    fun byId(id: String): CatalogModel? = models.firstOrNull { it.id == id }
}
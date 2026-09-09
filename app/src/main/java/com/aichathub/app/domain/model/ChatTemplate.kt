package com.aichathub.app.domain.model

/**
 * Prompt chat template the model was fine-tuned with. Using the model's own
 * template measurably improves output quality and instruction-following.
 */
enum class ChatTemplate {
    GENERIC,
    CHATML,
    GEMMA,
    LLAMA3,
    LLAMA2
}

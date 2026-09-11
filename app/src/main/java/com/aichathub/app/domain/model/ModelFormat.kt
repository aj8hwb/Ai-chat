package com.aichathub.app.domain.model

enum class ModelFormat(val extension: String) {
    TASK(".task"),
    LITERTLM(".litertlm"),
    TFLITE(".tflite"),
    GGUF(".gguf")
}

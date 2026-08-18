package com.aichathub.app.data.model

import com.aichathub.app.domain.model.ChatTemplate
import com.aichathub.app.domain.model.ModelFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every catalog entry must declare its chat template so the prompt builder
 * can wrap turns in the format the model was actually trained with. A model
 * left at the GENERIC default (except the genuine base / text-completion
 * models) is a catalog mistake, not a fallback.
 */
class LocalModelCatalogTest {

    @Test
    fun `every model declares a chat template`() {
        assertTrue(LocalModelCatalog.models.isNotEmpty())
        LocalModelCatalog.models.forEach { model ->
            assertTrue(
                "Model ${model.id} must declare a chat template",
                model.chatTemplate.name.isNotBlank()
            )
        }
    }

    @Test
    fun `instruct models use their real template`() {
        assertEquals(ChatTemplate.CHATML, LocalModelCatalog.byId("qwen2.5-0.5b-instruct")?.chatTemplate)
        assertEquals(ChatTemplate.CHATML, LocalModelCatalog.byId("qwen3-4b-uncensored")?.chatTemplate)
        assertEquals(ChatTemplate.GEMMA, LocalModelCatalog.byId("gemma-4-e4b-uncensored")?.chatTemplate)
        assertEquals(ChatTemplate.LLAMA3, LocalModelCatalog.byId("dolphin-3-cyber-8b")?.chatTemplate)
        assertEquals(ChatTemplate.LLAMA3, LocalModelCatalog.byId("dolphin-2.9.4-llama3.1-8b")?.chatTemplate)
        assertEquals(ChatTemplate.CHATML, LocalModelCatalog.byId("dolphin-2.8-mistral-7b")?.chatTemplate)
        assertEquals(ChatTemplate.CHATML, LocalModelCatalog.byId("smollm2-135m-instruct")?.chatTemplate)
        assertEquals(ChatTemplate.CHATML, LocalModelCatalog.byId("smollm2-360m-instruct")?.chatTemplate)
        assertEquals(ChatTemplate.LLAMA2, LocalModelCatalog.byId("tinyllama-1.1b-chat")?.chatTemplate)
    }

    @Test
    fun `base text-completion models stay generic`() {
        // OpenELM and MobileLLM are base (non-instruct) models — the flat
        // transcript is the correct, honest choice for them.
        assertEquals(ChatTemplate.GENERIC, LocalModelCatalog.byId("openelm-270m")?.chatTemplate)
        assertEquals(ChatTemplate.GENERIC, LocalModelCatalog.byId("openelm-450m")?.chatTemplate)
        assertEquals(ChatTemplate.GENERIC, LocalModelCatalog.byId("mobilellm-125m")?.chatTemplate)
        assertEquals(ChatTemplate.GENERIC, LocalModelCatalog.byId("mobilellm-350m")?.chatTemplate)
        assertEquals(ChatTemplate.GENERIC, LocalModelCatalog.byId("mobilellm-600m")?.chatTemplate)
    }

    @Test
    fun `all models are gguf for the bundled runtime`() {
        LocalModelCatalog.models.forEach { model ->
            assertEquals(ModelFormat.GGUF, model.format)
        }
    }
}
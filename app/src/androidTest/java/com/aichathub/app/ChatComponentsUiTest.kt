package com.aichathub.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.aichathub.app.ui.components.ChatInput
import com.aichathub.app.ui.theme.AiChatHubTheme
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented Compose UI tests for chat components.
 */
class ChatComponentsUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun chatInput_displaysPlaceholder() {
        composeTestRule.setContent {
            AiChatHubTheme {
                ChatInput(
                    inputText = "",
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    generating = false,
                    isModelLoaded = true
                )
            }
        }
        composeTestRule.onNodeWithText("Message...").assertIsDisplayed()
    }

    @Test
    fun chatInput_showsSendButton_whenNotGenerating() {
        composeTestRule.setContent {
            AiChatHubTheme {
                ChatInput(
                    inputText = "Hello",
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    generating = false,
                    isModelLoaded = true
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Send message").assertIsDisplayed()
    }

    @Test
    fun chatInput_showsStopButton_whenGenerating() {
        composeTestRule.setContent {
            AiChatHubTheme {
                ChatInput(
                    inputText = "Hello",
                    onInputChange = {},
                    onSend = {},
                    onStop = {},
                    generating = true,
                    isModelLoaded = true
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Stop generating").assertIsDisplayed()
    }
}

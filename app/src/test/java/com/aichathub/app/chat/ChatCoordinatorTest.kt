package com.aichathub.app.chat

import com.aichathub.app.data.ModelRepository
import com.aichathub.app.data.local.ConversationDao
import com.aichathub.app.data.local.ConversationEntity
import com.aichathub.app.data.local.MessageDao
import com.aichathub.app.data.local.MessageEntity
import com.aichathub.app.domain.model.CatalogModel
import com.aichathub.app.domain.model.ModelFormat
import com.aichathub.app.domain.model.ModelLifecycleState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ChatCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var runtime: InferenceRuntime
    private lateinit var conversationDao: ConversationDao
    private lateinit var messageDao: MessageDao
    private lateinit var modelRepository: ModelRepository
    private lateinit var coordinator: ChatCoordinator

    private val testModel = CatalogModel(
        id = "model-1",
        name = "Test Model",
        provider = "test",
        description = "A test model",
        parameters = "7B",
        category = "chat",
        format = ModelFormat.GGUF,
        quantization = "Q4_K_M",
        fileSizeBytes = 1_000_000_000L,
        estimatedMemoryBytes = 2_000_000_000L,
        contextLength = 4096,
        license = "MIT",
        licenseType = "MIT",
        officialRepositoryUrl = "https://example.com",
        downloadUrl = "https://example.com/model.gguf",
        fileName = "model.gguf",
        runtime = "llama",
        parameterCount = 7_000_000_000L,
        chatTemplate = com.aichathub.app.domain.model.ChatTemplate.CHATML
    )

    private val testConfig = GenerationConfig(
        temperature = 0.8f,
        maxTokens = 512
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        runtime = mockk(relaxed = true)
        conversationDao = mockk(relaxed = true)
        messageDao = mockk(relaxed = true)
        modelRepository = mockk(relaxed = true)

        every { runtime.activeModelId } returns null
        coEvery { runtime.load(any(), any(), any(), any(), any()) } returns Unit
        coEvery { runtime.unload() } returns Unit
        coEvery { runtime.generateStreaming(any(), any(), any()) } returns "Hello"
        coEvery { conversationDao.byId(any()) } returns ConversationEntity(
            id = 1L,
            title = "Test",
            modelId = "model-1",
            createdAt = 0L,
            updatedAt = 0L
        )
        coEvery { conversationDao.insert(any()) } returns 1L
        coEvery { messageDao.forConversation(any()) } returns emptyList()

        coordinator = ChatCoordinator(runtime, conversationDao, messageDao, modelRepository, kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun testFile(exists: Boolean = true, size: Long = 100L): File {
        val file = mockk<File>()
        every { file.exists() } returns exists
        every { file.length() } returns size
        every { file.absolutePath } returns "/tmp/model.gguf"
        return file
    }

    // ── stopGeneration tests ─────────────────────────────────────────

    @Test
    fun `stopGeneration sets STOPPING state`() = runTest {
        coordinator.stopGeneration()

        val state = coordinator.state.value
        assertEquals(ChatGenerationState.STOPPING, state.generationState)
    }

    @Test
    fun `stopGeneration calls cancelGeneration on runtime`() = runTest {
        coordinator.stopGeneration()

        coVerify { runtime.cancelGeneration() }
    }

    // ── double-send prevention ───────────────────────────────────────

    @Test
    fun `sendMessage throws if already generating`() = runTest {
        val file = testFile()
        coordinator.loadModel(testModel, file)

        // First send triggers GENERATING
        // We need to make the first send hang so we can test the second
        coEvery { runtime.generateStreaming(any(), any(), any()) } coAnswers {
            // Simulate a long-running generation
            kotlinx.coroutines.delay(10_000)
            "result"
        }

        val sendJob = kotlinx.coroutines.launch {
            try {
                coordinator.sendMessage(
                    prompt = "Hello",
                    config = testConfig,
                    systemPrompt = "You are helpful",
                    model = testModel,
                    onStream = {}
                )
            } catch (_: CancellationException) { }
        }

        // Wait for GENERATING state
        testDispatcher.scheduler.advanceTimeBy(100)
        testDispatcher.scheduler.runCurrent()
        assertEquals(ChatGenerationState.GENERATING, coordinator.state.value.generationState)

        // Second send must throw
        try {
            coordinator.sendMessage(
                prompt = "Hello again",
                config = testConfig,
                systemPrompt = "You are helpful",
                model = testModel,
                onStream = {}
            )
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("Already generating", e.message)
        }

        sendJob.cancel()
    }

    // ── state transitions ────────────────────────────────────────────

    @Test
    fun `loadModel transitions through LOADING then IDLE`() = runTest {
        val file = testFile()

        coordinator.loadModel(testModel, file)

        val state = coordinator.state.value
        assertEquals(ChatGenerationState.IDLE, state.generationState)
        assertEquals("model-1", state.activeModelId)
        assertEquals("Test Model", state.activeModelName)
        assertEquals(false, state.isLoadingModel)
    }

    @Test
    fun `loadModel sets LOADING state during load`() = runTest {
        val file = testFile()
        val loadLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { runtime.load(any(), any(), any(), any(), any()) } coAnswers {
            loadLatch.await()
        }

        val loadJob = kotlinx.coroutines.launch {
            try {
                coordinator.loadModel(testModel, file)
            } catch (_: Exception) { }
        }

        // Advance into the load call
        testDispatcher.scheduler.advanceTimeBy(100)
        testDispatcher.scheduler.runCurrent()

        assertEquals(ChatGenerationState.LOADING, coordinator.state.value.generationState)
        assertEquals(true, coordinator.state.value.isLoadingModel)

        loadLatch.complete(Unit)
        advanceUntilIdle()
        loadJob.cancel()
    }

    @Test
    fun `sendMessage transitions IDLE to GENERATING then DONE`() = runTest {
        val file = testFile()
        coordinator.loadModel(testModel, file)

        coEvery { runtime.generateStreaming(any(), any(), any()) } returns "Hello world"

        coordinator.sendMessage(
            prompt = "Hi",
            config = testConfig,
            systemPrompt = "System",
            model = testModel,
            onStream = {}
        )

        val state = coordinator.state.value
        assertEquals(ChatGenerationState.DONE, state.generationState)
        assertNull(state.error)
    }

    @Test
    fun `sendMessage sets ERROR on exception`() = runTest {
        val file = testFile()
        coordinator.loadModel(testModel, file)

        coEvery { runtime.generateStreaming(any(), any(), any()) } throws RuntimeException("boom")

        try {
            coordinator.sendMessage(
                prompt = "Hi",
                config = testConfig,
                systemPrompt = "System",
                model = testModel,
                onStream = {}
            )
            fail("Expected exception")
        } catch (e: RuntimeException) {
            assertEquals("boom", e.message)
        }

        val state = coordinator.state.value
        assertEquals(ChatGenerationState.ERROR, state.generationState)
        assertEquals("Generation failed. Please try again.", state.error)
    }

    // ── STOPPING through CancellationException ───────────────────────

    @Test
    fun `STOPPING state is maintained through CancellationException`() = runTest {
        val file = testFile()
        coordinator.loadModel(testModel, file)

        coEvery { runtime.generateStreaming(any(), any(), any()) } coAnswers {
            throw CancellationException("cancelled")
        }

        // Put the coordinator into STOPPING first
        coordinator.stopGeneration()
        assertEquals(ChatGenerationState.STOPPING, coordinator.state.value.generationState)

        try {
            coordinator.sendMessage(
                prompt = "Hi",
                config = testConfig,
                systemPrompt = "System",
                model = testModel,
                onStream = {}
            )
        } catch (_: CancellationException) { }

        // State should remain STOPPING (not reset to DONE)
        assertEquals(ChatGenerationState.STOPPING, coordinator.state.value.generationState)
    }

    @Test
    fun `CancellationException without STOPPING transitions to DONE`() = runTest {
        val file = testFile()
        coordinator.loadModel(testModel, file)

        coEvery { runtime.generateStreaming(any(), any(), any()) } coAnswers {
            throw CancellationException("cancelled")
        }

        try {
            coordinator.sendMessage(
                prompt = "Hi",
                config = testConfig,
                systemPrompt = "System",
                model = testModel,
                onStream = {}
            )
        } catch (_: CancellationException) { }

        // Without STOPPING, should transition to DONE
        assertEquals(ChatGenerationState.DONE, coordinator.state.value.generationState)
    }

    // ── model load error states ──────────────────────────────────────

    @Test
    fun `loadModel with missing file sets ERROR state`() = runTest {
        val file = testFile(exists = false)

        try {
            coordinator.loadModel(testModel, file)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("missing"))
        }

        val state = coordinator.state.value
        assertEquals(ChatGenerationState.ERROR, state.generationState)
        assertEquals(false, state.isLoadingModel)
    }

    @Test
    fun `loadModel with empty file sets ERROR state`() = runTest {
        val file = testFile(size = 0L)

        try {
            coordinator.loadModel(testModel, file)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("empty"))
        }

        assertEquals(ChatGenerationState.ERROR, coordinator.state.value.generationState)
    }

    @Test
    fun `loadModel runtime failure sets ERROR state`() = runTest {
        val file = testFile()
        coEvery { runtime.load(any(), any(), any(), any(), any()) } throws RuntimeException("load failed")

        try {
            coordinator.loadModel(testModel, file)
            fail("Expected exception")
        } catch (_: RuntimeException) { }

        val state = coordinator.state.value
        assertEquals(ChatGenerationState.ERROR, state.generationState)
        assertEquals("The model could not be loaded with the current device resources.", state.error)
    }

    // ── conversation management ──────────────────────────────────────

    @Test
    fun `createConversation inserts and sets active ID`() = runTest {
        val id = coordinator.createConversation("model-1", "My Chat")

        assertEquals(1L, id)
        assertEquals(1L, coordinator.activeConversationId.value)
    }

    @Test
    fun `newConversation clears active ID`() = runTest {
        coordinator.createConversation("model-1")
        assertEquals(1L, coordinator.activeConversationId.value)

        coordinator.newConversation()
        assertNull(coordinator.activeConversationId.value)
    }

    @Test
    fun `deleteConversation clears active ID when deleting active`() = runTest {
        coordinator.createConversation("model-1")
        val id = coordinator.activeConversationId.value!!

        coordinator.deleteConversation(id)

        assertNull(coordinator.activeConversationId.value)
        coVerify { messageDao.deleteForConversation(id) }
        coVerify { conversationDao.delete(id) }
    }

    // ── OOM handling ────────────────────────────────────────────────

    @Test
    fun `sendMessage OOM sets ERROR and unloads model`() = runTest {
        val file = testFile()
        coordinator.loadModel(testModel, file)

        coEvery { runtime.generateStreaming(any(), any(), any()) } throws OutOfMemoryError("oom")

        try {
            coordinator.sendMessage(
                prompt = "Hi",
                config = testConfig,
                systemPrompt = "System",
                model = testModel,
                onStream = {}
            )
            fail("Expected OutOfMemoryError")
        } catch (_: OutOfMemoryError) { }

        assertEquals(ChatGenerationState.ERROR, coordinator.state.value.generationState)
        assertEquals("Generation ran out of memory. Try a lighter model or a shorter message.", coordinator.state.value.error)
        coVerify { runtime.unload() }
    }

    // ── resetToIdle ─────────────────────────────────────────────────

    @Test
    fun `resetToIdle transitions to IDLE and clears error`() = runTest {
        coordinator.stopGeneration()
        assertEquals(ChatGenerationState.STOPPING, coordinator.state.value.generationState)

        coordinator.resetToIdle()

        assertEquals(ChatGenerationState.IDLE, coordinator.state.value.generationState)
        assertNull(coordinator.state.value.error)
    }
}

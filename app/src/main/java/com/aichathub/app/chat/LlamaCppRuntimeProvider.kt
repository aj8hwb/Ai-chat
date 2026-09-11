package com.aichathub.app.chat

import android.content.Context
import android.util.Log
import com.aichathub.app.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RuntimeProvider] for the llama.cpp inference engine.
 *
 * This is the primary on-device engine, wrapping the `org.codeshipping:llama-kotlin-android`
 * library. It supports CPU inference on all ARM64/x86_64 devices and OpenCL
 * acceleration on Adreno GPUs when available.
 *
 * The provider is injected via Hilt multibinding. Adding it to the app requires
 * a `@Binds @IntoSet` entry in the DI module.
 */
@Singleton
class LlamaCppRuntimeProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) : RuntimeProvider {

    companion object {
        private const val TAG = "LlamaCppRuntimeProvider"
    }

    override val id: String = "llama_cpp"

    override val displayName: String = "llama.cpp (CPU)"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override val supportedBackends: List<BackendType> = listOf(
        BackendType.CPU,
        BackendType.OPENCL_ADRENO
    )

    /**
     * llama.cpp is always available on supported architectures. A more
     * sophisticated check could verify the native library was loaded
     * successfully, but the current library ships its own .so and does not
     * require a separate install step.
     */
    override suspend fun isAvailable(): Boolean = true

    /**
     * Creates a new [LlamaCppRuntime] instance. Each call returns an
     * independent runtime that can load its own model and manage its own
     * lifecycle.
     *
     * The measured-memory callback writes PSS deltas to the settings
     * repository so the recommendation engine can prefer real-device
     * measurements over catalog estimates.
     */
    override suspend fun createRuntime(): InferenceRuntime {
        Log.i(TAG, "Creating LlamaCppRuntime instance")
        return LlamaCppRuntime(
            context = context,
            onModelMemoryMeasured = { modelId, bytes ->
                scope.launch {
                    runCatching {
                        settingsRepository.setMeasuredMemory(modelId, bytes)
                    }.onFailure { e ->
                        Log.w(TAG, "Failed to record measured memory for $modelId", e)
                    }
                }
            }
        )
    }
}

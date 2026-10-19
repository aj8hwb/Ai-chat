package com.aichathub.app.chat

import android.content.Context
import android.util.Log
import com.aichathub.app.data.SettingsRepository
import com.aichathub.app.device.ThermalManager
import com.aichathub.app.util.ModelThreads
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
 * library. It supports CPU inference only: the current native build of the
 * library does not expose a functional GPU backend, and LlamaCppRuntime
 * pins gpuLayers = 0. CPU is always the honest, reliable fallback.
 *
 * NOTE: GPU backends must NEVER be listed here until the native runtime
 * actually supports GPU layer offload AND the runtime verifies layers are
 * offloaded. Adreno hardware being present is not evidence of any acceleration.
 *
 * The provider is injected via Hilt multibinding. Adding it to the app requires
 * a `@Binds @IntoSet` entry in the DI module.
 */
@Singleton
class LlamaCppRuntimeProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val thermalManager: ThermalManager
) : RuntimeProvider {

    companion object {
        private const val TAG = "LlamaCppRuntimeProvider"
    }

    override val id: String = "llama_cpp"

    override val displayName: String = "llama.cpp (CPU)"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * CPU-only. OPENCL_ADRENO/VULKAN are deliberately NOT advertised: the
     * packaged native library initializes no GPU backend, so reporting them
     * would claim acceleration that is not actually active.
     */
    override val supportedBackends: List<BackendType> = listOf(
        BackendType.CPU
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
            },
            // Long-generation thermal watchdog: CRITICAL heat stops the native
            // generation via cancelGeneration (a supported action). Reduced
            // thread counts are NOT changed live — llama.cpp fixes threads at
            // context creation; the thermal policy applies to the NEXT load.
            thermalWatchdog = { thermalLevelOrNull(thermalManager) }
        )
    }

    private fun thermalLevelOrNull(manager: ThermalManager): ModelThreads.ThermalLoadLevel? =
        try {
            manager.currentLoadLevel()
        } catch (e: Exception) {
            Log.d(TAG, "Thermal watchdog probe failed: ${e.message}")
            null
        }
}

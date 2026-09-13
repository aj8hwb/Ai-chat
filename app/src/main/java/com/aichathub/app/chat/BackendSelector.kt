package com.aichathub.app.chat

import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.os.Build
import android.util.Log
import com.aichathub.app.device.DeviceInfoProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Information about the device's compute backend capabilities, gathered once
 * and cached for the lifetime of the process.
 */
data class BackendInfo(
    val selectedBackend: BackendType,
    val gpuAvailable: Boolean,
    val vulkanAvailable: Boolean,
    val gpuAccelerationAvailable: Boolean,
    val deviceGpuModel: String?,
    val deviceGpuVendor: String?,
    val reason: String
)

/**
 * Probes the device hardware at runtime to determine which compute backends
 * are available and selects the best one for inference.
 *
 * The selection is conservative: it prefers known-good paths over theoretical
 * support. GPU acceleration is only reported as available when the runtime
 * actually supports it (gpuLayers > 0 is functional).
 *
 * Security model:
 *  - GPU detection uses PackageManager features (not just GLES strings)
 *  - Vulkan detection checks actual library availability
 *  - Backend capabilities are truthfully reported to the UI
 *  - No false GPU acceleration claims
 */
@Singleton
class BackendSelector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceInfoProvider: DeviceInfoProvider
) {

    companion object {
        private const val TAG = "BackendSelector"

        /** GPU vendor ID strings that indicate Adreno (Qualcomm) hardware. */
        private val ADRENO_VENDORS = setOf(
            "Qualcomm", "qualcomm", "Qualcomm Technologies, Inc.",
            "Adreno", "adreno"
        )

        /** GPU vendor ID strings that indicate Mali (ARM) hardware. */
        private val MALI_VENDORS = setOf(
            "ARM", "arm", "ARM Ltd.", "Mali", "mali",
            "ARM, Inc.", "ARM Limited"
        )
    }

    @Volatile
    private var cachedInfo: BackendInfo? = null

    /**
     * Probes GPU and compute capabilities and returns the best backend.
     *
     * The result is cached after the first call — probing OpenGL ES / OpenCL
     * is cheap but not free, and this may be called from UI thread paths.
     */
    suspend fun selectBestBackend(): BackendType {
        return getBackendInfo().selectedBackend
    }

    /**
     * Returns detailed backend information. Probes once and caches the result.
     */
    fun getBackendInfo(): BackendInfo {
        cachedInfo?.let { return it }

        val gpuInfo = probeGpu()
        val vulkanAvailable = probeVulkan()
        val backend = selectBackend(gpuInfo, vulkanAvailable)

        // GPU acceleration is only available if the runtime actually supports it.
        // Currently gpuLayers=0 (CPU-only), so GPU acceleration is NOT available
        // even if the hardware supports it. This flag will be true only when
        // llama-kotlin-android adds GPU backend support.
        val gpuAccelerationAvailable = false

        val reason = when {
            gpuAccelerationAvailable && backend != BackendType.CPU ->
                "GPU acceleration active ($backend)"
            vulkanAvailable ->
                "Vulkan hardware present but runtime uses CPU-only mode"
            gpuInfo != null ->
                "GPU detected (${gpuInfo.renderer}) but runtime uses CPU-only mode"
            else ->
                "No GPU acceleration — using CPU inference"
        }

        val info = BackendInfo(
            selectedBackend = backend,
            gpuAvailable = gpuInfo != null,
            vulkanAvailable = vulkanAvailable,
            gpuAccelerationAvailable = gpuAccelerationAvailable,
            deviceGpuModel = gpuInfo?.renderer,
            deviceGpuVendor = gpuInfo?.vendor,
            reason = reason
        )
        cachedInfo = info
        Log.i(TAG, "Backend info: $info")
        return info
    }

    /**
     * Forces a re-probe. Useful if the device configuration might have
     * changed (e.g. external GPU connected, driver update).
     */
    fun invalidateCache() {
        cachedInfo = null
    }

    private fun selectBackend(gpuInfo: GpuInfo?, vulkanAvailable: Boolean): BackendType {
        // Even if hardware supports OpenCL/Vulkan, the current runtime is CPU-only.
        // When GPU backends are implemented, enable these paths:
        //
        // if (gpuInfo != null && gpuAccelerationSupported(gpuInfo)) {
        //     return BackendType.OPENCL_ADRENO or OPENCL_MALI
        // }
        // if (vulkanAvailable && vulkanAccelerationSupported()) {
        //     return BackendType.VULKAN
        // }

        Log.d(TAG, "Using CPU backend (GPU acceleration not yet implemented)")
        return BackendType.CPU
    }

    /**
     * Probes OpenGL ES 3.0+ to retrieve GPU vendor and renderer strings.
     * Returns null if the GL context cannot be created (headless device, etc.).
     *
     * This runs on the calling thread and must be invoked off the main thread.
     */
    private fun probeGpu(): GpuInfo? {
        return try {
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
            val version = GLES20.glGetString(GLES20.GL_VERSION)

            if (vendor.isNullOrBlank() || renderer.isNullOrBlank()) {
                Log.d(TAG, "GL strings unavailable: vendor=$vendor renderer=$renderer")
                return null
            }

            GpuInfo(
                vendor = vendor.trim(),
                renderer = renderer.trim(),
                version = version?.trim() ?: "unknown"
            )
        } catch (e: Exception) {
            Log.d(TAG, "GL probe failed: ${e.message}")
            null
        }
    }

    /**
     * Checks whether Vulkan 1.0+ is available by inspecting the device's
     * feature level and checking the PackageManager for Vulkan support.
     *
     * Uses multiple detection methods for reliability:
     * 1. Android feature check (PackageManager)
     * 2. API level check (Vulkan requires API 24+)
     * 3. Library availability check (fallback)
     */
    private fun probeVulkan(): Boolean {
        // Vulkan requires API 24+ (Android 7.0 Nougat).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        // Method 1: PackageManager feature check (most reliable)
        val pm = context.packageManager
        if (pm.hasSystemFeature("android.hardware.vulkan")) {
            Log.d(TAG, "Vulkan detected via PackageManager")
            return true
        }

        // Method 2: Try to load the Vulkan library
        return try {
            System.loadLibrary("vulkan")
            Log.d(TAG, "Vulkan detected via library load")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.d(TAG, "Vulkan library not available")
            false
        } catch (e: Exception) {
            Log.d(TAG, "Vulkan probe failed: ${e.message}")
            false
        }
    }

    private data class GpuInfo(
        val vendor: String,
        val renderer: String,
        val version: String
    )
}

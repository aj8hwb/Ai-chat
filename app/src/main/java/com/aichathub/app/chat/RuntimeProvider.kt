package com.aichathub.app.chat

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backend types supported by inference runtimes.
 * Used for device capability matching and runtime selection.
 */
enum class BackendType {
    CPU,
    OPENCL_ADRENO,
    OPENCL_MALI,
    VULKAN,
    UNKNOWN;

    companion object {
        private const val TAG = "BackendType"

        /**
         * Maps a vendor-specific GPU identifier string to a [BackendType].
         * Returns [UNKNOWN] if the vendor is not recognized.
         */
        fun fromVendorId(vendorId: String): BackendType = when {
            vendorId.contains("qualcomm", ignoreCase = true) ||
            vendorId.contains("adreno", ignoreCase = true) -> OPENCL_ADRENO
            vendorId.contains("arm", ignoreCase = true) ||
            vendorId.contains("mali", ignoreCase = true) -> OPENCL_MALI
            vendorId.contains("vulkan", ignoreCase = true) -> VULKAN
            else -> {
                Log.d(TAG, "Unrecognized GPU vendor: $vendorId")
                UNKNOWN
            }
        }
    }
}

/**
 * Describes a single inference runtime and its capabilities.
 *
 * Each provider is responsible for:
 * - Reporting which backends it supports
 * - Checking runtime availability on the current device
 * - Creating a fresh [InferenceRuntime] instance when selected
 *
 * Providers are registered via Hilt's `Set<RuntimeProvider>` multibinding
 * so adding a new engine requires only a new class + `@Binds` entry.
 */
interface RuntimeProvider {

    /** Stable machine-readable identifier (e.g. "llama_cpp", "mnn", "execu_torch"). */
    val id: String

    /** Human-readable label shown in settings / UI. */
    val displayName: String

    /** Backends this provider can use on capable hardware. */
    val supportedBackends: List<BackendType>

    /**
     * Quick availability check. Called before showing the provider to the user.
     * Must be fast — no heavy I/O. Implementations may cache results after the
     * first call within a session.
     */
    suspend fun isAvailable(): Boolean

    /**
     * Creates a new [InferenceRuntime] instance. The caller owns the returned
     * runtime and is responsible for calling [InferenceRuntime.release] when
     * done. Each call MUST return a fresh, independent instance.
     */
    suspend fun createRuntime(): InferenceRuntime
}

/**
 * Central registry of all available [RuntimeProvider] instances.
 *
 * Hilt injects the full set of providers discovered via multibinding. The
 * registry provides lookup helpers so consumers never deal with the raw set
 * directly.
 */
@Singleton
class RuntimeProviderRegistry @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards RuntimeProvider>
) {

    companion object {
        private const val TAG = "RuntimeProviderRegistry"
    }

    private val providersById: Map<String, RuntimeProvider> by lazy {
        providers.associateBy { it.id }
    }

    /** All registered providers (regardless of availability). */
    fun getAllProviders(): List<RuntimeProvider> = providers.toList()

    /** Returns a single provider by its [RuntimeProvider.id], or null. */
    fun getProvider(id: String): RuntimeProvider? = providersById[id]

    /**
     * Filters providers to those currently available on this device.
     * Availability is checked concurrently; providers that throw during
     * [RuntimeProvider.isAvailable] are silently excluded.
     */
    suspend fun getAvailableProviders(): List<RuntimeProvider> {
        return providers.filter { provider ->
            runCatching {
                provider.isAvailable()
            }.onFailure { e ->
                Log.w(TAG, "Provider ${provider.id} availability check failed", e)
            }.getOrDefault(false)
        }
    }

    /**
     * Selects the best available provider, optionally preferring a specific
     * [preferredBackend].
     *
     * Selection strategy:
     * 1. Among available providers, find those that support the preferred
     *    backend. If found, return the first match.
     * 2. If no preferred backend or no match, return the first available
     *    provider (providers are injected in a deterministic order via a
     *    `Set`, so the order is stable within a build).
     * 3. If no provider is available, return null.
     */
    suspend fun getBestProvider(
        preferredBackend: BackendType? = null
    ): RuntimeProvider? {
        val available = getAvailableProviders()
        if (available.isEmpty()) {
            Log.w(TAG, "No runtime provider available")
            return null
        }

        if (preferredBackend != null && preferredBackend != BackendType.UNKNOWN) {
            val match = available.firstOrNull { provider ->
                provider.supportedBackends.contains(preferredBackend)
            }
            if (match != null) {
                Log.i(TAG, "Selected provider ${match.id} for backend $preferredBackend")
                return match
            }
            Log.d(TAG, "No provider supports preferred backend $preferredBackend, using fallback")
        }

        return available.first().also {
            Log.i(TAG, "Selected default provider: ${it.id}")
        }
    }
}

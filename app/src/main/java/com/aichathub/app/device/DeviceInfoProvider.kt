package com.aichathub.app.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.aichathub.app.domain.model.DeviceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Collects real device information. No values are fabricated: anything that
 * the platform does not reliably expose is reported as a safe default.
 */
class DeviceInfoProvider(private val context: Context) {

    /** Returns real hardware / storage information from the device. */
    suspend fun getDeviceProfile(): DeviceProfile = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val storageTotal = storageTotal()
        val storageAvailable = storageAvailable()

        DeviceProfile(
            totalRamBytes = memInfo.totalMem,
            availableRamBytes = memInfo.availMem,
            storageTotalBytes = storageTotal,
            storageAvailableBytes = storageAvailable,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            androidVersion = Build.VERSION.SDK_INT
        )
    }

    private fun storageTotal(): Long =
        try {
            StatFs(Environment.getDataDirectory().absolutePath).let { stat ->
                stat.blockCountLong * stat.blockSizeLong
            }
        } catch (e: Exception) {
            0L
        }

    private fun storageAvailable(): Long =
        try {
            StatFs(Environment.getDataDirectory().absolutePath).let { stat ->
                stat.availableBlocksLong * stat.blockSizeLong
            }
        } catch (e: Exception) {
            0L
        }
}

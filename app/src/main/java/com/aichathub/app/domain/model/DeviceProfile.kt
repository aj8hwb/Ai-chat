package com.aichathub.app.domain.model

/** A snapshot of the device's current capabilities. */
data class DeviceProfile(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val storageTotalBytes: Long,
    val storageAvailableBytes: Long,
    val cpuCores: Int,
    val abi: String,
    val androidVersion: Int,
    val isLowRamDevice: Boolean = totalRamBytes <= (4L * 1024 * 1024 * 1024)
) {
    val availableRamGb: Float get() = availableRamBytes / (1024f * 1024f * 1024f)
    val totalRamGb: Float get() = totalRamBytes / (1024f * 1024f * 1024f)
}

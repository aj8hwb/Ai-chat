package com.aichathub.app.domain.model

/** Lifecycle state of an installed model on the device. */
enum class ModelLifecycleState {
    NOT_INSTALLED,
    DOWNLOADING,
    DOWNLOADED,
    VERIFYING,
    INSTALLED,
    LOADING,
    READY,
    RUNNING,
    UNLOADING,
    ERROR
}

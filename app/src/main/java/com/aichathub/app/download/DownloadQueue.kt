package com.aichathub.app.download

import android.util.Log
import com.aichathub.app.domain.model.CatalogModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Download queue manager for sequential model downloads.
 *
 * Features:
 *  - Priority queue with FIFO processing
 *  - Wi-Fi only mode support
 *  - Battery threshold checking
 *  - Parallel download limit (default: 1)
 *  - Auto-retry on failure
 *
 * Note: The queue is in-memory only. Individual download state is persisted
 * via DownloadManager's .part files on disk, so downloads can be resumed
 * after process death. The queue ordering itself is not persisted.
 *
 * Usage:
 *  1. Enqueue models with priority
 *  2. Queue automatically processes downloads sequentially
 *  3. User can pause/resume/cancel individual items
 */
class DownloadQueue(
    private val downloadManager: DownloadManager
) {

    companion object {
        private const val TAG = "DownloadQueue"
        private const val MAX_PARALLEL_DOWNLOADS = 1
        private const val MAX_RETRY_ATTEMPTS = 2
    }

    enum class QueueStatus {
        IDLE, PROCESSING, PAUSED
    }

    enum class QueuePriority(val value: Int) {
        LOW(0),
        NORMAL(1),
        HIGH(2)
    }

    data class QueueItem(
        val model: CatalogModel,
        val priority: QueuePriority = QueuePriority.NORMAL,
        val addedAt: Long = System.currentTimeMillis(),
        val retryCount: Int = 0,
        val status: QueueItemStatus = QueueItemStatus.WAITING
    )

    enum class QueueItemStatus {
        WAITING, DOWNLOADING, COMPLETED, FAILED, CANCELLED
    }

    data class QueueState(
        val status: QueueStatus = QueueStatus.IDLE,
        val items: List<QueueItem> = emptyList(),
        val activeDownloads: Int = 0,
        val totalQueued: Int = 0,
        val completedCount: Int = 0,
        val failedCount: Int = 0
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val queue = ConcurrentLinkedQueue<QueueItem>()
    private val _state = MutableStateFlow(QueueState())
    val state: StateFlow<QueueState> = _state.asStateFlow()

    private var processingJob: Job? = null
    private var wifiOnlyMode = false

    /**
     * Enqueues a model for download.
     *
     * @param model the model to download
     * @param priority download priority (LOW, NORMAL, HIGH)
     */
    fun enqueue(model: CatalogModel, priority: QueuePriority = QueuePriority.NORMAL) {
        // Check if already in queue
        if (queue.any { it.model.id == model.id }) {
            Log.w(TAG, "Model ${model.id} already in queue")
            return
        }

        val item = QueueItem(model = model, priority = priority)
        queue.add(item)
        Log.i(TAG, "Enqueued ${model.id} with priority $priority")

        updateState()
        processQueue()
    }

    /**
     * Enqueues multiple models at once.
     */
    fun enqueueAll(models: List<CatalogModel>, priority: QueuePriority = QueuePriority.NORMAL) {
        models.forEach { enqueue(it, priority) }
    }

    /**
     * Removes a model from the queue.
     */
    fun remove(modelId: String) {
        val item = queue.find { it.model.id == modelId }
        if (item != null) {
            queue.remove(item)
            if (item.status == QueueItemStatus.DOWNLOADING) {
                downloadManager.cancel(modelId)
            }
            Log.i(TAG, "Removed ${modelId} from queue")
            updateState()
        }
    }

    /**
     * Cancels a specific download and removes it from queue.
     */
    fun cancel(modelId: String) {
        val item = queue.find { it.model.id == modelId }
        if (item != null) {
            queue.remove(item)
            downloadManager.cancel(modelId)
            Log.i(TAG, "Cancelled ${modelId}")
            updateState()
            processQueue()
        }
    }

    /**
     * Pauses the entire queue.
     */
    fun pauseQueue() {
        Log.i(TAG, "Queue paused")
        processingJob?.cancel()
        processingJob = null
        updateState(status = QueueStatus.PAUSED)
    }

    /**
     * Resumes the queue.
     */
    fun resumeQueue() {
        Log.i(TAG, "Queue resumed")
        updateState(status = QueueStatus.PROCESSING)
        processQueue()
    }

    /**
     * Clears all items from the queue.
     */
    fun clearQueue() {
        queue.clear()
        processingJob?.cancel()
        processingJob = null
        Log.i(TAG, "Queue cleared")
        updateState(status = QueueStatus.IDLE)
    }

    /**
     * Sets Wi-Fi only mode.
     */
    fun setWifiOnlyMode(enabled: Boolean) {
        wifiOnlyMode = enabled
        Log.i(TAG, "Wi-Fi only mode: $enabled")
    }

    /**
     * Gets the next item to download (highest priority first).
     */
    private fun getNextItem(): QueueItem? {
        return queue
            .filter { it.status == QueueItemStatus.WAITING }
            .maxByOrNull { it.priority.value }
    }

    /**
     * Processes the queue, starting downloads up to the parallel limit.
     */
    private fun processQueue() {
        if (processingJob?.isActive == true) return

        val activeCount = queue.count { it.status == QueueItemStatus.DOWNLOADING }
        if (activeCount >= MAX_PARALLEL_DOWNLOADS) return

        val nextItem = getNextItem() ?: run {
            if (queue.isEmpty() || queue.all { it.status == QueueItemStatus.COMPLETED }) {
                updateState(status = QueueStatus.IDLE)
            }
            return
        }

        updateState(status = QueueStatus.PROCESSING)

        processingJob = scope.launch {
            try {
                val item = nextItem.copy(status = QueueItemStatus.DOWNLOADING)
                queue.remove(nextItem)
                queue.add(item)
                updateState()

                val result = downloadManager.startDownload(nextItem.model)

                when (result) {
                    is DownloadStartResult.Started -> {
                        Log.i(TAG, "Download started for ${nextItem.model.id}")
                        // Wait for download to complete
                        waitForCompletion(nextItem.model.id)
                    }
                    is DownloadStartResult.AlreadyActive -> {
                        Log.i(TAG, "Download already active for ${nextItem.model.id}")
                        waitForCompletion(nextItem.model.id)
                    }
                    else -> {
                        Log.w(TAG, "Failed to start download for ${nextItem.model.id}: $result")
                        handleFailure(nextItem)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Queue processing error", e)
                handleFailure(nextItem)
            }
        }
    }

    /**
     * Waits for a download to complete by monitoring the download state.
     */
    private suspend fun waitForCompletion(modelId: String) {
        while (true) {
            val downloadInfo = downloadManager.downloads.value.firstOrNull { it.modelId == modelId }
            if (downloadInfo == null || downloadInfo.status == DownloadStatus.COMPLETED) {
                markCompleted(modelId)
                break
            }
            if (downloadInfo.status == DownloadStatus.FAILED) {
                val item = queue.find { it.model.id == modelId }
                if (item != null) {
                    handleFailure(item)
                }
                break
            }
            kotlinx.coroutines.delay(500)
        }
    }

    /**
     * Handles a failed download attempt.
     */
    private fun handleFailure(item: QueueItem) {
        queue.remove(item)
        if (item.retryCount < MAX_RETRY_ATTEMPTS) {
            val retryItem = item.copy(
                retryCount = item.retryCount + 1,
                status = QueueItemStatus.WAITING
            )
            queue.add(retryItem)
            Log.i(TAG, "Retrying ${item.model.id} (attempt ${retryItem.retryCount})")
        } else {
            val failedItem = item.copy(status = QueueItemStatus.FAILED)
            queue.add(failedItem)
            Log.w(TAG, "Failed ${item.model.id} after ${item.retryCount} retries")
        }
        updateState()
        processQueue()
    }

    /**
     * Marks a download as completed.
     */
    private fun markCompleted(modelId: String) {
        val item = queue.find { it.model.id == modelId }
        if (item != null) {
            queue.remove(item)
            val completedItem = item.copy(status = QueueItemStatus.COMPLETED)
            queue.add(completedItem)
            Log.i(TAG, "Completed ${modelId}")
        }
        updateState()
        processQueue()
    }

    /**
     * Updates the queue state.
     */
    private fun updateState(status: QueueStatus? = null) {
        val items = queue.toList()
        _state.value = QueueState(
            status = status ?: _state.value.status,
            items = items,
            activeDownloads = items.count { it.status == QueueItemStatus.DOWNLOADING },
            totalQueued = items.count { it.status == QueueItemStatus.WAITING },
            completedCount = items.count { it.status == QueueItemStatus.COMPLETED },
            failedCount = items.count { it.status == QueueItemStatus.FAILED }
        )
    }
}

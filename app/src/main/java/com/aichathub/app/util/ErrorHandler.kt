package com.aichathub.app.util

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/**
 * Comprehensive error handling utility for the application.
 * Provides structured error types, error reporting, and retry mechanisms.
 */
object ErrorHandler {
    private const val TAG = "ErrorHandler"

    /**
     * Sealed class representing different types of errors
     */
    sealed class AppError(
        val message: String,
        val cause: Throwable? = null,
        val isRetryable: Boolean = false
    ) {
        class NetworkError(message: String, cause: Throwable? = null) : AppError(
            message = message,
            cause = cause,
            isRetryable = true
        )

        class DatabaseError(message: String, cause: Throwable? = null) : AppError(
            message = message,
            cause = cause,
            isRetryable = false
        )

        class ModelError(message: String, cause: Throwable? = null) : AppError(
            message = message,
            cause = cause,
            isRetryable = true
        )

        class DownloadError(message: String, cause: Throwable? = null) : AppError(
            message = message,
            cause = cause,
            isRetryable = true
        )

        class MemoryError(message: String, cause: Throwable? = null) : AppError(
            message = message,
            cause = cause,
            isRetryable = false
        )

        class ValidationError(message: String) : AppError(
            message = message,
            isRetryable = false
        )

        class UnknownError(message: String, cause: Throwable? = null) : AppError(
            message = message,
            cause = cause,
            isRetryable = true
        )
    }

    /**
     * Result wrapper for operations that can fail
     */
    sealed class Result<out T> {
        data class Success<T>(val data: T) : Result<T>()
        data class Error(val error: AppError) : Result<Nothing>()
        data object Loading : Result<Nothing>()
    }

    /**
     * Error state for UI components
     */
    data class ErrorState(
        val error: AppError? = null,
        val isShowing: Boolean = false
    )

    private val _errorState = MutableStateFlow(ErrorState())
    val errorState: StateFlow<ErrorState> = _errorState.asStateFlow()

    /**
     * Handle an exception and convert it to an AppError
     */
    fun handleException(exception: Throwable): AppError {
        return when (exception) {
            is CancellationException -> throw exception // Don't handle cancellation
            is IOException -> AppError.NetworkError(
                message = "Network error: ${exception.localizedMessage}",
                cause = exception
            )
            is OutOfMemoryError -> AppError.MemoryError(
                message = "Insufficient memory. Try a lighter model.",
                cause = exception
            )
            is SecurityException -> AppError.DatabaseError(
                message = "Permission denied: ${exception.localizedMessage}",
                cause = exception
            )
            else -> {
                Log.e(TAG, "Unhandled exception", exception)
                AppError.UnknownError(
                    message = exception.localizedMessage ?: "An unexpected error occurred",
                    cause = exception
                )
            }
        }
    }

    /**
     * Show an error to the user
     */
    fun showError(error: AppError) {
        _errorState.value = ErrorState(error = error, isShowing = true)
        Log.e(TAG, "Error: ${error.message}", error.cause)
    }

    /**
     * Dismiss the current error
     */
    fun dismissError() {
        _errorState.value = ErrorState()
    }

    /**
     * Execute a suspend function with error handling
     */
    suspend fun <T> executeWithErrorHandling(
        operation: suspend () -> T,
        onError: ((AppError) -> Unit)? = null
    ): Result<T> {
        return try {
            val result = operation()
            Result.Success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = handleException(e)
            onError?.invoke(error) ?: showError(error)
            Result.Error(error)
        }
    }

    /**
     * Get user-friendly error message
     */
    fun getUserFriendlyMessage(error: AppError): String {
        return when (error) {
            is AppError.NetworkError -> "Network connection failed. Please check your internet connection and try again."
            is AppError.DatabaseError -> "Data storage error. Please try again."
            is AppError.ModelError -> "Model error: ${error.message}"
            is AppError.DownloadError -> "Download failed. Please try again."
            is AppError.MemoryError -> "Insufficient memory. Try a lighter model or close other apps."
            is AppError.ValidationError -> error.message
            is AppError.UnknownError -> "Something went wrong. Please try again."
        }
    }

    /**
     * Retry mechanism with exponential backoff
     */
    suspend fun <T> retryWithBackoff(
        maxRetries: Int = 3,
        initialDelayMs: Long = 1000,
        maxDelayMs: Long = 10000,
        factor: Double = 2.0,
        operation: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                return operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Retry attempt ${attempt + 1} failed", e)

                if (attempt < maxRetries - 1) {
                    Thread.sleep(currentDelay)
                    currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
                }
            }
        }

        throw lastException ?: IllegalStateException("Retry failed without exception")
    }
}

/**
 * Extension function for Result to handle success and error cases
 */
inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> {
    if (this is ErrorHandler.Result.Success) action(data)
    return this
}

inline fun <T> Result<T>.onError(action: (ErrorHandler.AppError) -> Unit): Result<T> {
    if (this is ErrorHandler.Result.Error) action(error)
    return this
}

/**
 * Extension function to convert Result to nullable value
 */
fun <T> Result<T>.getOrNull(): T? = when (this) {
    is ErrorHandler.Result.Success -> data
    else -> null
}

/**
 * Extension function to get value or default
 */
fun <T> Result<T>.getOrDefault(default: T): T = when (this) {
    is ErrorHandler.Result.Success -> data
    else -> default
}

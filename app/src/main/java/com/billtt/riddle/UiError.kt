package com.billtt.riddle

import android.content.Context
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/** Carry a resource identifier, never display an untranslated server/SDK message. */
open class UiError(val resource: Int, vararg val arguments: Any) : IOException() {
    companion object {
        fun describe(context: Context, error: Throwable): String = when (error) {
            is UiError -> context.getString(error.resource, *error.arguments)
            is SocketTimeoutException, is TimeoutException,
            is kotlinx.coroutines.TimeoutCancellationException -> context.getString(R.string.error_timeout)
            is IOException -> context.getString(R.string.error_network)
            else -> context.getString(R.string.error_unexpected)
        }
    }
}

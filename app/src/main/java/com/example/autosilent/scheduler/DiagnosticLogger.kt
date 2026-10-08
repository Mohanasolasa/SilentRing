package com.example.autosilent.scheduler

/**
 * Diagnostic logger for tracing scheduler lifecycle, AlarmManager events, and state reconciliations.
 * Safe across both Android runtime and JVM unit test environments.
 */
object DiagnosticLogger {
    var logger: (tag: String, message: String) -> Unit = { tag, message ->
        try {
            android.util.Log.i(tag, message)
        } catch (_: Throwable) {
            println("[$tag] $message")
        }
    }

    fun d(tag: String, message: String) = logger(tag, message)
    fun i(tag: String, message: String) = logger(tag, message)
    fun w(tag: String, message: String) = logger(tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        val msg = if (throwable != null) "$message: ${throwable.message}" else message
        logger(tag, msg)
    }
}

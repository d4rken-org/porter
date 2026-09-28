package eu.darken.porter.manager.utils

import android.util.Log
import eu.darken.porter.manager.BuildConfig
import java.util.Locale
import rikka.shizuku.server.util.Logger as ServiceLogger

val LOGGER = Logger("PorterManager")

class Logger(private val tag: String) {

    fun isLoggable(tag: String, level: Int): Boolean = level > Log.DEBUG || debugEnabled()

    fun v(msg: String) {
        if (isLoggable(tag, Log.VERBOSE)) println(Log.VERBOSE, tag, msg)
    }

    fun v(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.VERBOSE)) println(Log.VERBOSE, tag, String.format(Locale.ENGLISH, fmt, *args))
    }

    fun v(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.VERBOSE)) println(Log.VERBOSE, tag, msg, tr)
    }

    fun d(msg: String) {
        if (isLoggable(tag, Log.DEBUG)) println(Log.DEBUG, tag, msg)
    }

    fun d(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.DEBUG)) println(Log.DEBUG, tag, String.format(Locale.ENGLISH, fmt, *args))
    }

    fun d(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.DEBUG)) println(Log.DEBUG, tag, msg, tr)
    }

    fun i(msg: String) {
        if (isLoggable(tag, Log.INFO)) Log.i(tag, msg)
    }

    fun i(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.INFO)) Log.i(tag, String.format(Locale.ENGLISH, fmt, *args))
    }

    fun i(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.INFO)) Log.i(tag, msg, tr)
    }

    fun w(msg: String) {
        if (isLoggable(tag, Log.WARN)) Log.w(tag, msg)
    }

    fun w(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.WARN)) Log.w(tag, String.format(Locale.ENGLISH, fmt, *args))
    }

    fun w(tr: Throwable?, fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.WARN)) Log.w(tag, String.format(Locale.ENGLISH, fmt, *args), tr)
    }

    fun w(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.WARN)) Log.w(tag, msg, tr)
    }

    fun e(msg: String) {
        if (isLoggable(tag, Log.ERROR)) Log.e(tag, msg)
    }

    fun e(fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.ERROR)) Log.e(tag, String.format(Locale.ENGLISH, fmt, *args))
    }

    fun e(msg: String, tr: Throwable?) {
        if (isLoggable(tag, Log.ERROR)) Log.e(tag, msg, tr)
    }

    fun e(tr: Throwable?, fmt: String, vararg args: Any?) {
        if (isLoggable(tag, Log.ERROR)) Log.e(tag, String.format(Locale.ENGLISH, fmt, *args), tr)
    }

    companion object {

        /** Whether a debug log is being recorded. */
        @Volatile
        internal var recording = false
            set(value) {
                field = value
                ServiceLogger.setDebugAlways(debugEnabled())
            }

        // Keeps the service logger, as used in this process, on the same gate.
        init {
            ServiceLogger.setDebugAlways(debugEnabled())
        }

        fun debugEnabled(): Boolean = BuildConfig.DEBUG || recording

        fun println(priority: Int, tag: String, msg: String, tr: Throwable? = null) {
            Log.println(priority, tag, if (tr == null) msg else msg + '\n' + Log.getStackTraceString(tr))
        }
    }
}

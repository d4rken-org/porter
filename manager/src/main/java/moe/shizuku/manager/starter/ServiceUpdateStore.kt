package moe.shizuku.manager.starter

import android.content.SharedPreferences

internal class ServiceUpdateStore(private val preferences: SharedPreferences, private val target: String) {
    enum class Phase { PREFLIGHT, LAUNCHED, FAILED, SUCCEEDED }
    data class Record(val phase: Phase, val detail: String?)

    fun read(): Record? {
        if (preferences.getString("target", null) != target) return null
        val phase = preferences.getString("phase", null)?.let { runCatching { Phase.valueOf(it) }.getOrNull() } ?: return null
        return Record(phase, preferences.getString("detail", null))
    }

    fun write(phase: Phase, detail: String? = null): Boolean = preferences.edit()
        .putString("target", target).putString("phase", phase.name).putString("detail", detail).commit()
}

package moe.shizuku.manager.adb

import android.content.SharedPreferences
import androidx.core.content.edit

internal data class TvPairingResult(val success: Boolean, val message: String)

internal class TvPairingResultStore(private val preferences: SharedPreferences) {
    fun read(): TvPairingResult? {
        val message = preferences.getString("tv_pairing_result_message", null) ?: return null
        return TvPairingResult(preferences.getBoolean("tv_pairing_result_success", false), message)
    }

    fun save(result: TvPairingResult) = preferences.edit {
        putBoolean("tv_pairing_result_success", result.success)
        putString("tv_pairing_result_message", result.message)
    }

    fun clear() = preferences.edit {
        remove("tv_pairing_result_success")
        remove("tv_pairing_result_message")
    }
}

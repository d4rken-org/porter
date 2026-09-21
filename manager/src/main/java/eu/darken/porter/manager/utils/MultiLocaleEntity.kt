package eu.darken.porter.manager.utils

import eu.darken.porter.manager.PorterSettings
import java.util.LinkedHashMap
import java.util.Locale

class MultiLocaleEntity : LinkedHashMap<String, String>() {

    fun interface LocaleProvider {
        fun get(): Locale
    }

    /** Every caller formats or opens the result, where a missing entry failed as a null before. */
    fun get(): String {
        val locale = localeProvider.get()
        return get(locale) ?: throw NullPointerException("no entry for $locale")
    }

    fun get(locale: Locale): String? {
        if (isEmpty()) return null

        val language = locale.language
        val region = locale.country

        // fully match
        val full = Locale(language, region)
        for (l in keys) {
            if (full.toString() == l.replace('-', '_')) {
                return get(l)
            }
        }

        // match language only keys
        val languageOnly = Locale(language)
        for (l in keys) {
            if (languageOnly.toString() == l) {
                return get(l)
            }
        }

        // match a language_region with only language
        for (l in keys) {
            if (l.startsWith(languageOnly.toString())) {
                return get(l)
            }
        }

        if (containsKey("en")) {
            return get("en")
        }

        if (containsKey("default")) {
            return get("default")
        }

        for (key in keys) {
            if ("overwrite_default" != key) return get(key)
        }
        return null
    }

    companion object {
        val DEFAULT_LOCAL_PROVIDER = LocaleProvider { PorterSettings.locale }

        var localeProvider: LocaleProvider = DEFAULT_LOCAL_PROVIDER
    }
}

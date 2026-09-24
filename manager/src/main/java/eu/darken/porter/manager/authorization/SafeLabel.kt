package eu.darken.porter.manager.authorization

/** The most of an app's label the prompt considers, as the platform does for its own dialogs. */
internal const val MAX_LABEL_LENGTH = 500

/**
 * [label] as one line an app cannot dress up as another's name: `"\u202EGood app\nis Porter"`
 * becomes `"Good app"`.
 */
internal fun safeLabel(label: CharSequence, maxLength: Int = MAX_LABEL_LENGTH): String {
    val kept = StringBuilder()
    for (c in label) {
        if (c in LINE_BREAKS) break
        if (c.isISOControl() || c in BIDI_CONTROLS) continue
        kept.append(c)
    }
    val line = kept.trim()
    if (line.length <= maxLength) return line.toString()
    val end = if (Character.isHighSurrogate(line[maxLength - 1])) maxLength - 1 else maxLength
    return line.substring(0, end).trimEnd()
}

private const val LINE_BREAKS = "\n\r\u000B\u000C\u0085\u2028\u2029"
private const val BIDI_CONTROLS = "\u200E\u200F\u061C\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"

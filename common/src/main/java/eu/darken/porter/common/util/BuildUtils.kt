package eu.darken.porter.common.util

import android.os.Build

object BuildUtils {

    private val SDK = Build.VERSION.SDK_INT

    private val PREVIEW_SDK = if (SDK >= 23) Build.VERSION.PREVIEW_SDK_INT else 0

    fun atLeast31(): Boolean = SDK >= 31 || SDK == 30 && PREVIEW_SDK > 0

    fun atLeast30(): Boolean = SDK >= 30

    fun atLeast29(): Boolean = SDK >= 29

    fun atLeast28(): Boolean = SDK >= 28

    fun atLeast26(): Boolean = SDK >= 26

    fun atLeast24(): Boolean = SDK >= 24

    fun atLeast23(): Boolean = SDK >= 23
}

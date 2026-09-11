package moe.shizuku.manager.model

import eu.darken.porter.common.PorterBuildIdentity
import moe.shizuku.manager.BuildConfig

data class PorterServiceVersion(val name: String, val code: Int, val buildId: String? = null) {
    fun matches(installed: PorterServiceVersion): Boolean = this == installed

    companion object {
        val installed = PorterServiceVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE,
            PorterBuildIdentity.ID + ":" + BuildConfig.BUILD_TYPE)
    }
}

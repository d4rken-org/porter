package eu.darken.porter.privileged.util

import android.content.pm.UserInfo

internal fun userInfos(vararg ids: Int): List<UserInfo> = ids.map { id -> UserInfo().apply { this.id = id } }

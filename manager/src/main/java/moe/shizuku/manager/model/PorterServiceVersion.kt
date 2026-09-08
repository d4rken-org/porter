package moe.shizuku.manager.model

data class PorterServiceVersion(val name: String, val code: Int) {
    fun matches(installedName: String, installedCode: Int): Boolean =
        name == installedName && code == installedCode
}

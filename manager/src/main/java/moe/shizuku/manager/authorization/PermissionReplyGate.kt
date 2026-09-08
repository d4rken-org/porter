package moe.shizuku.manager.authorization

internal class PermissionReplyGate(replied: Boolean = false) {
    var replied: Boolean = replied
        private set

    fun reply(send: () -> Unit): Boolean {
        if (replied) return false
        replied = true
        send()
        return true
    }
}

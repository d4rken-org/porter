package moe.shizuku.manager.service

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.shizuku.manager.starter.ServiceReplacement
import moe.shizuku.manager.ui.ComposeActivity
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.shizuku.Shizuku

class ServiceActivity : ComposeActivity() {
    override val protectTouches = true
    private val repository by lazy { ServiceStatusRepository.get(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        porterContent {
            val snapshot by repository.state.collectAsStateWithLifecycle()
            var dialog by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(snapshot.canStop) { if (!snapshot.canStop && dialog == "stop") dialog = null }
            LaunchedEffect(snapshot.canUpdate) { if (!snapshot.canUpdate && dialog == "update") dialog = null }
            ServiceScreenContent(snapshot, actions = ServiceActions(
                onBack = { finish() },
                onUpdate = { if (repository.state.value.canUpdate) dialog = "update" },
                onStop = { if (repository.state.value.canStop) dialog = "stop" },
            ))
            if (dialog == "update" && snapshot.canUpdate) UpdateServiceDialog(snapshot.failed, { dialog = null }) {
                dialog = null
                if (repository.state.value.canUpdate) ServiceReplacement.startManual()
            }
            if (dialog == "stop" && snapshot.canStop) StopServiceDialog(!snapshot.primaryUser, { dialog = null }) {
                dialog = null
                if (repository.state.value.canStop) {
                    ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
                    runCatching { Shizuku.exit() }.onFailure { ShizukuStateMachine.update() }
                }
            }
        }
    }
    override fun onResume() { super.onResume(); repository.refresh() }
}

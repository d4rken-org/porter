package moe.shizuku.manager.home

import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.core.res.resolveColor
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.databinding.HomeServerStatusBinding
import moe.shizuku.manager.model.ServiceStatus
import rikka.html.text.HtmlCompat
import rikka.html.text.toHtml
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuApiConstants

class ServerStatusViewHolder(private val binding: HomeServerStatusBinding, root: View) :
    BaseViewHolder<ServiceStatus>(root) {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeServerStatusBinding.inflate(inflater, outer.root, true)
            ServerStatusViewHolder(inner, outer.root)
        }
    }

    private inline val textView get() = binding.text1
    private inline val summaryView get() = binding.text2
    private inline val iconView get() = binding.icon

    override fun onBind() {
        val context = itemView.context
        val status = data
        val ok = status.isRunning
        val isRoot = status.uid == 0
        val apiVersion = status.apiVersion
        val patchVersion = status.patchVersion
        val restricted = ok && !status.permission
        iconView.setImageResource(when {
            restricted -> R.drawable.ic_warning_24
            ok -> R.drawable.ic_server_ok_24dp
            else -> R.drawable.ic_server_error_24dp
        })
        iconView.imageTintList = ColorStateList.valueOf(when {
            restricted -> ContextCompat.getColor(context, R.color.porter_status_warning)
            ok -> ContextCompat.getColor(context, R.color.porter_status_running)
            else -> context.theme.resolveColor(R.attr.colorOnSurfaceVariant)
        })
        iconView.backgroundTintList = ColorStateList.valueOf(when {
            restricted -> ContextCompat.getColor(context, R.color.porter_status_warning_container)
            ok -> ContextCompat.getColor(context, R.color.porter_status_running_container)
            else -> context.theme.resolveColor(R.attr.colorSurfaceContainerHighest)
        })
        val user = if (isRoot) "root" else "adb"
        val title = if (ok) {
            context.getString(R.string.home_status_service_is_running, context.getString(R.string.app_name))
        } else {
            context.getString(R.string.home_status_service_not_running, context.getString(R.string.app_name))
        }
        val summary = if (ok) {
            if (apiVersion != Shizuku.getLatestServiceVersion() || status.patchVersion != ShizukuApiConstants.SERVER_PATCH_VERSION) {
                context.getString(
                    R.string.home_status_service_version_update, user,
                    "${apiVersion}.${patchVersion}",
                    "${Shizuku.getLatestServiceVersion()}.${ShizukuApiConstants.SERVER_PATCH_VERSION}"
                )
            } else {
                context.getString(R.string.home_status_service_version, user, "${apiVersion}.${patchVersion}")
            }
        } else {
            ""
        }
        textView.text = title.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        val details = listOfNotNull(
            summary.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE).toString().takeIf { it.isNotEmpty() },
            context.getString(R.string.porter_status_restricted).takeIf { restricted }
        ).joinToString("\n\n")
        summaryView.text = if (ok) {
            "$details\n${context.getString(R.string.porter_status_details_hint)}"
        } else ""
        itemView.setOnClickListener(if (ok) View.OnClickListener {
            if (!ShizukuStateMachine.isRunning()) return@OnClickListener
            MaterialAlertDialogBuilder(context)
                .setTitle(textView.text)
                .setMessage("$details\n\n${context.getString(R.string.porter_status_stop_message)}")
                .setPositiveButton(R.string.action_stop) { _, _ ->
                    if (ShizukuStateMachine.isRunning()) {
                        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
                        runCatching { Shizuku.exit() }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else null)
        itemView.isClickable = ok
        itemView.isFocusable = ok
        if (TextUtils.isEmpty(summaryView.text)) {
            summaryView.visibility = View.GONE
        } else {
            summaryView.visibility = View.VISIBLE
        }
    }
}

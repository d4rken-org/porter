package moe.shizuku.manager.settings

import android.app.Dialog
import android.os.Bundle
import android.os.Process
import android.text.method.LinkMovementMethod
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.Helps
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.AboutDialogBinding
import moe.shizuku.manager.ktx.toHtml
import moe.shizuku.manager.utils.AppIconCache

class AboutDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val binding = AboutDialogBinding.inflate(layoutInflater)
        binding.sourceCode.movementMethod = LinkMovementMethod.getInstance()
        binding.sourceCode.text = getString(
            R.string.about_view_source_code,
            "<b><a href=\"${Helps.SOURCE}\">GitHub</a></b>",
        ).toHtml()
        binding.icon.setImageBitmap(AppIconCache.getOrLoadBitmap(
            context, context.applicationInfo, Process.myUid() / 100000,
            resources.getDimensionPixelOffset(R.dimen.default_app_icon_size),
        ))
        binding.versionName.text = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        binding.btnClose.setOnClickListener { dismiss() }
        return MaterialAlertDialogBuilder(context).setView(binding.root).create()
    }
}

package moe.shizuku.manager.shell

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import moe.shizuku.manager.R
import moe.shizuku.manager.ui.*
import rikka.compatibility.DeviceCompatibility

class ShellTutorialActivity : ComposeActivity() {
    private val model: ShellTutorialViewModel by viewModels()
    private val openDocumentsTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree -> tree?.let(model::export) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        porterContent {
            val busy by model.busy.collectAsStateWithLifecycle()
            val error by model.error.collectAsStateWithLifecycle()
            PorterScaffold(stringResource(R.string.home_terminal_title), onBack = { finish() }) { padding ->
                Column(Modifier.padding(padding).consumeWindowInsets(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HtmlText(stringResource(R.string.rish_description, "rish"))
                    if (DeviceCompatibility.isMiui()) {
                        HtmlText(stringResource(R.string.terminal_tutorial_miui))
                        HtmlText(stringResource(R.string.terminal_tutorial_miui_2))
                    }
                    Text(stringResource(R.string.terminal_tutorial_1), style = MaterialTheme.typography.titleMedium)
                    HtmlText(stringResource(R.string.terminal_tutorial_1_description, "rish", "rish_shizuku.dex"))
                    Button(onClick = { openDocumentsTree.launch(null) }, enabled = !busy) { Text(stringResource(R.string.terminal_export_files)) }
                    HtmlText(stringResource(R.string.terminal_tutorial_2, "rish"))
                    SelectionContainer { Text("cp /sdcard/chosen-folder/* /data/data/terminal.package.name/files", fontFamily = FontFamily.Monospace) }
                    HtmlText(stringResource(R.string.terminal_tutorial_2_description, "rish", "rish", ".bashrc"))
                    Text(stringResource(R.string.terminal_tutorial_3), style = MaterialTheme.typography.titleMedium)
                    SelectionContainer { Text("sh /path/to/rish", fontFamily = FontFamily.Monospace) }
                }
            }
            error?.let { MessageDialog(stringResource(R.string.porter_support_error), it, { model.error.value = null }) }
        }
    }
}

class ShellTutorialViewModel(application: Application) : AndroidViewModel(application) {
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    fun export(tree: Uri) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val cr = context.contentResolver
                    val docId = DocumentsContract.getTreeDocumentId(tree)
                    val doc = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                    val child = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
                    val names = listOf("rish", "rish_shizuku.dex")
                    cr.query(child, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                        while (it.moveToNext()) if (it.getString(1) in names) {
                            check(DocumentsContract.deleteDocument(cr, DocumentsContract.buildDocumentUriUsingTree(tree, it.getString(0))))
                        }
                    }
                    names.forEach { name ->
                        val target = checkNotNull(DocumentsContract.createDocument(cr, doc, "application/octet-stream", name))
                        checkNotNull(cr.openOutputStream(target)).use { output ->
                            context.assets.open(name).use { input ->
                                if (name == "rish") output.write(input.bufferedReader().readText().replace("MANAGER_PKG", context.packageName).toByteArray())
                                else input.copyTo(output)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.localizedMessage ?: e.javaClass.simpleName }
            finally { busy.value = false }
        }
    }
}

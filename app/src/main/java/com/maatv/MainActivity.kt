package com.maatv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import com.maatv.ui.ImportDialog
import com.maatv.ui.RunOverlay
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.maatv.ui.EditorScreen
import com.maatv.ui.MaaTheme
import com.maatv.ui.MomScreen
import com.maatv.ui.SetupScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { MaaTheme { App(vm) } }
        if (savedInstanceState == null) handleShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShared(intent)
    }

    /** A shows file opened from WhatsApp, Files, Drive… goes straight to the import preview. */
    private fun handleShared(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
        uri?.let(vm::openImport)
    }
}

private enum class Page { Mom, Setup }

@Composable
private fun App(vm: AppViewModel) {
    val data by vm.data.collectAsState()
    val editor by vm.editor.collectAsState()
    // First run: nothing to show Mom yet, so start in setup.
    var page by remember { mutableStateOf(if (data.tiles.isEmpty()) Page.Setup else Page.Mom) }

    val importPreview by vm.importPreview.collectAsState()
    val tvApps by vm.tvApps.collectAsState()
    val run by vm.run.collectAsState()

    // Redraw everything when the language changes (texts are read from I18n while composing).
    key(data.language) { Screens(vm, editor != null, page, onPage = { page = it }) }

    // "Test on TV" from Settings shows the same progress card as Mom's screen.
    if (page == Page.Setup && editor == null) run?.let { RunOverlay(vm, it, onStop = vm::stop, onClose = vm::dismissRun) }
    importPreview?.let { ImportDialog(vm, it, tvApps) }
}

@Composable
private fun Screens(vm: AppViewModel, editing: Boolean, page: Page, onPage: (Page) -> Unit) {
    when {
        editing -> {
            BackHandler { vm.closeEditor() }
            EditorScreen(vm)
        }
        page == Page.Setup -> {
            BackHandler { onPage(Page.Mom) }
            SetupScreen(vm, onDone = { onPage(Page.Mom) })
        }
        else -> MomScreen(vm, onOpenSetup = { onPage(Page.Setup) })
    }
}

package com.maatv.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maatv.AppViewModel
import com.maatv.Conn
import com.maatv.data.Step
import com.maatv.data.Tile

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(vm: AppViewModel, onDone: () -> Unit) {
    val data by vm.data.collectAsState()
    val conn by vm.conn.collectAsState()
    val scan by vm.scan.collectAsState()
    val tvApps by vm.tvApps.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::openImport)
    }
    var ip by remember { mutableStateOf(data.tvIp) }
    var scanning by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(data.tvIp.isBlank()) }
    var pin by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Tile?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "Settings",
            leading = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleIconButton(Ic.Back, "Back", size = 44.dp, onClick = onDone)
                    Spacer(Modifier.width(12.dp))
                    AppLogo(36.dp)
                }
            },
            trailing = { GradientButton("Done", height = 44.dp, icon = Ic.Check, onClick = onDone) },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- TV ----
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Surface3),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Ic.Tv, null, tint = Accent, modifier = Modifier.size(26.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Television", color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        val (dot, label) = when (val c = conn) {
                            Conn.Connected -> Good to "Connected · ${data.tvIp}"
                            Conn.Connecting -> Warn to "Connecting… check the TV for “Allow”"
                            is Conn.Failed -> Bad to "Not connected · ${c.reason}"
                            Conn.Idle -> TextLow to "Not connected yet"
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(dot),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(label, color = TextMid, fontSize = 13.sp, maxLines = 2)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("TV IP address") },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TonalButton("Find TV", Modifier.weight(1f), icon = Ic.Wifi) { scanning = true; vm.scanNetwork() }
                    GradientButton("Connect", Modifier.weight(1f), height = 48.dp) { vm.connect(ip) }
                }
                if (scanning) {
                    Spacer(Modifier.height(12.dp))
                    val found = scan
                    when {
                        found == null -> Text("Searching your Wi-Fi…", color = TextMid, fontSize = 14.sp)
                        found.isEmpty() -> Text("No TV found. Is USB debugging on, and is this phone on the same Wi-Fi?", color = Bad, fontSize = 14.sp)
                        else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            found.forEach { addr ->
                                Pill(
                                    addr,
                                    Modifier.clickable { ip = addr; vm.connect(addr) },
                                    dot = Accent,
                                    background = Surface3,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (showHelp) "Hide TV setup steps" else "How to set up the TV",
                    color = Accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { showHelp = !showHelp }
                        .padding(vertical = 6.dp),
                )
                AnimatedVisibility(showHelp) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Surface2)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        HelpStep(1, "On the TV: Settings → System → About → click “Android TV OS build” 7 times.")
                        HelpStep(2, "Settings → System → Developer options → turn on USB debugging.")
                        HelpStep(3, "Keep the TV and this phone on the same Wi-Fi.")
                        HelpStep(4, "Tap Connect. On the TV, tick “Always allow” and press Allow.")
                    }
                }
            }

            // ---- Shows ----
            Panel {
                PanelTitle("Shows", "What Mom sees on her screen, in this order.")
                if (data.tiles.isEmpty()) {
                    Text("No shows yet. Connect the TV, then add one.", color = TextMid, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                }
                data.tiles.forEachIndexed { i, tile ->
                    ShowRow(
                        vm,
                        tile,
                        missingApp = tvApps?.let { apps ->
                            tile.steps.filterIsInstance<Step.LaunchApp>().firstOrNull()?.takeIf { it.pkg !in apps }?.appName
                        },
                        onTest = { vm.play(tile) },
                        canUp = i > 0,
                        canDown = i < data.tiles.lastIndex,
                        onUp = { vm.moveTile(tile.id, -1) },
                        onDown = { vm.moveTile(tile.id, 1) },
                        onEdit = { vm.openEditor(tile.id) },
                        onDelete = { deleting = tile },
                    )
                    Spacer(Modifier.height(10.dp))
                }
                GradientButton(
                    "Add a show",
                    Modifier.fillMaxWidth(),
                    icon = Ic.Add,
                    enabled = conn == Conn.Connected,
                ) { vm.openEditor(null) }
                if (conn != Conn.Connected) {
                    Text("Connect the TV first.", color = TextLow, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }

            // ---- Share ----
            Panel {
                PanelTitle(
                    "Share shows",
                    "Send all shows to another phone (WhatsApp, Drive, email…), so nobody has to record them again. " +
                        "Your TV address and PIN are not included.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GradientButton("Share shows", Modifier.weight(1f), height = 48.dp, enabled = data.tiles.isNotEmpty()) {
                        scope.launch {
                            val file = vm.exportShows()
                            val uri = FileProvider.getUriForFile(context, "com.maatv.files", file)
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("application/octet-stream")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .putExtra(Intent.EXTRA_SUBJECT, "Maa TV shows")
                                .putExtra(Intent.EXTRA_TEXT, "Maa TV shows: open this file with the Maa TV app to add them.")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, "Share shows"))
                        }
                    }
                    TonalButton("Import shows", Modifier.weight(1f), icon = Ic.Add) { pickFile.launch(arrayOf("*/*")) }
                }
            }

            // ---- Language ----
            Panel {
                PanelTitle("Language", "Language of Mom's screen and messages.")
                LanguageToggle(data.language == "hi", { vm.setLanguage(if (it) "hi" else "en") }, large = true)
            }

            // ---- PIN ----
            Panel {
                PanelTitle("Settings PIN", "Needed to open settings from Mom's screen (lock icon). Default is 1234.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { v -> pin = v.filter(Char::isDigit).take(8) },
                        label = { Text("New PIN") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    GradientButton("Save", height = 52.dp, enabled = pin.length >= 4) { vm.setPin(pin); pin = "" }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    deleting?.let { tile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Surface1,
            shape = RoundedCornerShape(28.dp),
            icon = { Icon(Ic.Delete, null, tint = Bad) },
            title = { Text("Delete “${tile.name}”?", color = TextHi) },
            text = { Text("It will disappear from Mom's screen.", color = TextMid) },
            confirmButton = { TextButton(onClick = { vm.deleteTile(tile.id); deleting = null }) { Text("Delete", color = Bad) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = TextMid) } },
        )
    }
}

@Composable
fun TopBar(title: String, leading: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(14.dp))
        Text(title, color = TextHi, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun HelpStep(n: Int, text: String) {
    Row {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(50))
                .background(Surface3),
            contentAlignment = Alignment.Center,
        ) { Text("$n", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(10.dp))
        Text(text, color = TextMid, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun ShowRow(
    vm: AppViewModel,
    tile: Tile,
    missingApp: String?,
    onTest: () -> Unit,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Surface2)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bmp = remember(tile.poster) { vm.poster(tile.poster) }
        Box(
            Modifier
                .size(52.dp, 68.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Surface3),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(tile.name.take(1), color = Accent, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tile.name, color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 2)
            val app = tile.steps.filterIsInstance<Step.LaunchApp>().firstOrNull()?.appName ?: "?"
            Text(
                "$app · ${tile.steps.size} steps" + (tile.lastRunSeconds?.let { " · last run $it s" } ?: ""),
                color = TextLow,
                fontSize = 13.sp,
            )
            missingApp?.let { Text("$it is not installed on this TV", color = Bad, fontSize = 12.sp) }
            Text(
                "Test on TV",
                color = Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(onClick = onTest)
                    .padding(vertical = 4.dp),
            )
        }
        Column {
            Row {
                CircleIconButton(Ic.Up, "Move up", size = 36.dp, enabled = canUp, onClick = onUp)
                Spacer(Modifier.width(6.dp))
                CircleIconButton(Ic.Down, "Move down", size = 36.dp, enabled = canDown, onClick = onDown)
            }
            Spacer(Modifier.height(6.dp))
            Row {
                CircleIconButton(Ic.Edit, "Edit", size = 36.dp, onClick = onEdit)
                Spacer(Modifier.width(6.dp))
                CircleIconButton(Ic.Delete, "Delete", size = 36.dp, tint = Bad, onClick = onDelete)
            }
        }
    }
}

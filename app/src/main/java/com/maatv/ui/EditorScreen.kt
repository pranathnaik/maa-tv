package com.maatv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maatv.AppViewModel
import com.maatv.data.Step
import com.maatv.data.describe
import com.maatv.tv.UiScreen

/**
 * The recorder. You drive the TV from a live picture of it; every action is
 * recorded as a step, and the finished list becomes one tile on Mom's screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: AppViewModel) {
    val ed by vm.editor.collectAsState()
    val state = ed ?: return
    var query by remember { mutableStateOf("") }
    var pickApp by remember { mutableStateOf(state.steps.isEmpty()) }
    val busy = state.busy != null

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = if (state.steps.isEmpty()) "New show" else "Edit show",
            leading = { CircleIconButton(Ic.Close, "Cancel", size = 44.dp, onClick = vm::closeEditor) },
            trailing = { GradientButton("Save", height = 44.dp, icon = Ic.Check, enabled = !busy) { vm.saveEditor() } },
        )
        // Status strip: what the TV is doing, or what went wrong.
        if (busy) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(state.busy!!, color = TextMid, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(50)),
                    color = Accent,
                    trackColor = Surface3,
                    strokeCap = StrokeCap.Round,
                )
            }
        }
        state.error?.let {
            Row(
                Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Bad.copy(alpha = 0.15f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Ic.Close, null, tint = Bad, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(it, color = Bad, fontSize = 14.sp)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- 1. App ----
            Panel {
                StepTitle(1, "Choose the app")
                if (pickApp || state.steps.none { it is Step.LaunchApp }) {
                    if (state.apps.isEmpty() && !busy) Text("No apps loaded. Is the TV connected?", color = TextMid, fontSize = 14.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.apps.forEach { app ->
                            Pill(
                                app.name,
                                Modifier.clickable(enabled = !busy) { pickApp = false; vm.startWithApp(app) },
                                background = Surface3,
                                fontSize = 15.sp,
                            )
                        }
                    }
                } else {
                    val launch = state.steps.first { it is Step.LaunchApp } as Step.LaunchApp
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(launch.appName, dot = Good, background = Surface3, fontSize = 15.sp)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Change",
                            color = Accent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clickable { pickApp = true }
                                .padding(8.dp),
                        )
                    }
                }
            }

            // ---- 2. Live TV ----
            Panel {
                StepTitle(2, "Drive the TV", "Tap anything in the picture to select it on the TV (profile, a result, “Play”). Hold an item to use it as the picture.")
                TvMirror(
                    state.mirror,
                    state.screen,
                    enabled = !busy,
                    onTap = { x, y -> state.screen.at(x, y)?.let(vm::selectOnMirror) },
                    onHold = { x, y -> state.screen.at(x, y)?.let(vm::usePosterFrom) },
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TonalButton("Refresh", Modifier.weight(1f), icon = Ic.Refresh, enabled = !busy, onClick = vm::refreshMirror)
                    TonalButton("Wait 3s", Modifier.weight(1f), icon = Ic.Timer, enabled = !busy) { vm.doStep(Step.Wait(3)) }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Show name (English)") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GradientButton("Search in app", Modifier.weight(1.3f), icon = Ic.Search, height = 48.dp, enabled = !busy && query.isNotBlank()) {
                        vm.doStep(Step.Search(query.trim()), label = "Searching in the app…")
                    }
                    TonalButton("Type", Modifier.weight(1f), icon = Ic.Keyboard, enabled = !busy && query.isNotBlank()) {
                        vm.doStep(Step.TypeText(query.trim()))
                    }
                }
                Spacer(Modifier.height(10.dp))
                TonalButton("Play episode (resume / latest / first)", Modifier.fillMaxWidth(), icon = Ic.Play, enabled = !busy) {
                    vm.doStep(Step.Play, label = "Starting the episode…")
                }
                Spacer(Modifier.height(20.dp))
                Text("Remote · every press is recorded", color = TextMid, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(12.dp))
                RemotePad(enabled = !busy) { key -> vm.doStep(Step.Key(key), label = "Pressing a button…") }
            }

            // ---- 3. Name and picture ----
            Panel {
                StepTitle(3, "Name and picture")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(78.dp, 104.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Surface3),
                        contentAlignment = Alignment.Center,
                    ) {
                        state.poster?.let {
                            Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } ?: Icon(Ic.Tv, null, tint = TextLow)
                    }
                    Spacer(Modifier.width(14.dp))
                    OutlinedTextField(
                        value = state.name,
                        onValueChange = vm::setEditorName,
                        label = { Text("Name Mom sees") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ---- Steps ----
            Panel {
                StepTitle(4, "Recorded steps", "“Test” closes the app and replays everything, exactly like Mom's tap.")
                if (state.steps.isEmpty()) Text("Nothing recorded yet.", color = TextLow, fontSize = 14.sp)
                state.steps.forEachIndexed { i, step ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Surface3),
                            contentAlignment = Alignment.Center,
                        ) { Text("${i + 1}", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                        Spacer(Modifier.width(12.dp))
                        Text(step.describe(), color = TextHi, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        CircleIconButton(Ic.Close, "Remove", size = 34.dp, tint = TextMid, enabled = !busy) { vm.removeStep(i) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TonalButton("Undo", Modifier.weight(1f), icon = Ic.Undo, enabled = !busy && state.steps.isNotEmpty(), onClick = vm::undoStep)
                    GradientButton("Test", Modifier.weight(1f), icon = Ic.Play, height = 48.dp, enabled = !busy && state.steps.isNotEmpty(), onClick = vm::testEditor)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StepTitle(n: Int, title: String, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(AccentBrush),
            contentAlignment = Alignment.Center,
        ) { Text("$n", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Text(title, color = TextHi, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
    subtitle?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, color = TextMid, fontSize = 13.sp, lineHeight = 19.sp)
    }
    Spacer(Modifier.height(14.dp))
}

/** A picture of the TV screen. Taps are converted into TV screen coordinates. */
@Composable
private fun TvMirror(
    bmp: android.graphics.Bitmap?,
    screen: UiScreen,
    enabled: Boolean,
    onTap: (Int, Int) -> Unit,
    onHold: (Int, Int) -> Unit,
) {
    val ratio = bmp?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black)
            .border(BorderStroke(1.dp, Outline), RoundedCornerShape(20.dp))
            .pointerInput(screen, enabled) {
                if (!enabled) return@pointerInput
                fun toTv(o: Offset): Pair<Int, Int> {
                    val s = screen.screen
                    return (s.l + o.x / size.width * s.w).toInt() to (s.t + o.y / size.height * s.h).toInt()
                }
                detectTapGestures(
                    onTap = { o -> toTv(o).let { (x, y) -> onTap(x, y) } },
                    onLongPress = { o -> toTv(o).let { (x, y) -> onHold(x, y) } },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bmp == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Ic.Tv, null, tint = TextLow, modifier = Modifier.size(36.dp))
                Spacer(Modifier.height(8.dp))
                Text("Pick an app to see the TV here", color = TextLow, fontSize = 13.sp)
            }
        } else {
            Image(bmp.asImageBitmap(), "TV screen", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            // Outline what can be selected, and the TV's current focus in the accent color.
            Canvas(Modifier.fillMaxSize()) {
                val s = screen.screen
                if (s.w <= 0 || s.h <= 0) return@Canvas
                val sx = size.width / s.w
                val sy = size.height / s.h
                screen.labels().forEach { n ->
                    drawRoundRect(
                        Color.White.copy(alpha = 0.3f),
                        Offset((n.box.l - s.l) * sx, (n.box.t - s.t) * sy),
                        Size(n.box.w * sx, n.box.h * sy),
                        CornerRadius(4.dp.toPx()),
                        style = Stroke(1.dp.toPx()),
                    )
                }
                screen.focused()?.let { f ->
                    drawRoundRect(
                        Accent,
                        Offset((f.box.l - s.l) * sx, (f.box.t - s.t) * sy),
                        Size(f.box.w * sx, f.box.h * sy),
                        CornerRadius(6.dp.toPx()),
                        style = Stroke(3.dp.toPx()),
                    )
                }
            }
        }
    }
}

/** A round D-pad like a real remote, plus Back / Home / Play-Pause. */
@Composable
private fun RemotePad(enabled: Boolean, onKey: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(212.dp)
                .clip(CircleShape)
                .background(Surface2)
                .border(BorderStroke(1.dp, Outline), CircleShape),
        ) {
            CircleIconButton(Ic.Up, "Up", Modifier.align(Alignment.TopCenter).padding(top = 10.dp), size = 54.dp, enabled = enabled) { onKey("DPAD_UP") }
            CircleIconButton(Ic.Down, "Down", Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), size = 54.dp, enabled = enabled) { onKey("DPAD_DOWN") }
            CircleIconButton(Ic.Left, "Left", Modifier.align(Alignment.CenterStart).padding(start = 10.dp), size = 54.dp, enabled = enabled) { onKey("DPAD_LEFT") }
            CircleIconButton(Ic.Right, "Right", Modifier.align(Alignment.CenterEnd).padding(end = 10.dp), size = 54.dp, enabled = enabled) { onKey("DPAD_RIGHT") }
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(AccentBrush)
                    .clickable(enabled = enabled) { onKey("DPAD_CENTER") },
                contentAlignment = Alignment.Center,
            ) { Text("OK", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CircleIconButton(Ic.Back, "Back", enabled = enabled) { onKey("BACK") }
            CircleIconButton(Ic.Home, "Home", enabled = enabled) { onKey("HOME") }
            CircleIconButton(Ic.PlayPause, "Play/Pause", enabled = enabled) { onKey("MEDIA_PLAY_PAUSE") }
        }
    }
}

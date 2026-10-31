package com.maatv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maatv.AppViewModel
import com.maatv.Conn
import com.maatv.RunState
import com.maatv.data.Step
import com.maatv.data.Tile
import java.time.LocalTime

@Composable
fun MomScreen(vm: AppViewModel, onOpenSetup: () -> Unit) {
    val data by vm.data.collectAsState()
    val conn by vm.conn.collectAsState()
    val run by vm.run.collectAsState()
    var askPin by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 170.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Header(
                    conn = conn,
                    hindi = data.language == "hi",
                    onLanguage = { vm.setLanguage(if (it) "hi" else "en") },
                    onLock = { askPin = true },
                    onRetry = vm::reconnect,
                )
            }
            if (data.tiles.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(onOpenSetup) }
            } else {
                items(data.tiles, key = { it.id }) { tile -> PosterTile(vm, tile) { vm.play(tile) } }
            }
        }

        RemoteDock(
            vm,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )

        AnimatedVisibility(run != null, enter = fadeIn(), exit = fadeOut()) {
            run?.let { RunOverlay(vm, it, onStop = vm::stop, onClose = vm::dismissRun) }
        }
    }

    if (askPin) {
        PinDialog(
            correct = data.pin,
            onDismiss = { askPin = false },
            onOk = { askPin = false; onOpenSetup() },
        )
    }
}

@Composable
private fun Header(conn: Conn, hindi: Boolean, onLanguage: (Boolean) -> Unit, onLock: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.padding(top = 8.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppLogo(44.dp)
            Spacer(Modifier.width(10.dp))
            Text("Maa TV", color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.weight(1f))
            LanguageToggle(hindi, onLanguage)
            Spacer(Modifier.width(8.dp))
            CircleIconButton(Ic.Lock, t("Settings", "सेटिंग"), size = 40.dp, tint = TextMid, onClick = onLock)
        }
        Spacer(Modifier.height(14.dp))
        TvStatusCard(conn, onRetry)
        Spacer(Modifier.height(16.dp))
        val hour = LocalTime.now().hour
        Text(
            when {
                hour < 12 -> t("Good morning", "सुप्रभात")
                hour < 17 -> t("Good afternoon", "नमस्ते")
                else -> t("Good evening", "शुभ संध्या")
            },
            color = TextMid,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            t("What would you like to watch?", "आज क्या देखना है?"),
            color = TextHi,
            fontSize = 21.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Big, coloured, impossible-to-miss TV status: green when ready, amber connecting, red when not found. */
@Composable
private fun TvStatusCard(conn: Conn, onRetry: () -> Unit) {
    val color = when (conn) {
        Conn.Connected -> Good
        Conn.Connecting -> Warn
        else -> Bad
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.14f))
            .border(BorderStroke(1.5.dp, color.copy(alpha = 0.55f)), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // A soft ring that pulses while the TV is connected (or being connected).
            if (conn == Conn.Connected || conn == Conn.Connecting) {
                val pulse = rememberInfiniteTransition(label = "pulse")
                val scale by pulse.animateFloat(
                    1f, 1.45f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "scale",
                )
                val alpha by pulse.animateFloat(
                    0.45f, 0f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "alpha",
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(CircleShape)
                        .background(color.copy(alpha = alpha)),
                )
            }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Ic.Tv, null, tint = Bg, modifier = Modifier.size(21.dp))
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(Bg)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (conn == Conn.Connected) Ic.Check else if (conn == Conn.Connecting) Ic.Refresh else Ic.Close,
                    null,
                    tint = Bg,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when (conn) {
                    Conn.Connected -> t("TV is connected", "TV जुड़ा है")
                    Conn.Connecting -> t("Connecting to TV…", "TV से जुड़ रहे हैं…")
                    else -> t("TV not connected", "TV नहीं जुड़ा")
                },
                color = color,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                when (conn) {
                    Conn.Connected -> t("Ready. Tap a show to watch.", "तैयार है। शो पर दबाएँ।")
                    Conn.Connecting -> t("Please wait a moment.", "थोड़ा रुकिए।")
                    else -> t("Turn on the TV, then tap Retry.", "TV चालू करें, फिर दोबारा दबाएँ।")
                },
                color = TextMid,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
        }
        if (conn != Conn.Connected && conn != Conn.Connecting) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Bad)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) { Text(t("Retry", "दोबारा"), color = Bg, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun EmptyState(onOpenSetup: () -> Unit) {
    Panel(Modifier.padding(top = 24.dp), padding = 28.dp) {
        Icon(Ic.Tv, null, tint = Accent, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        Text(t("No shows yet", "अभी कोई शो नहीं"), color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            t("Add shows in settings and they will appear here.", "सेटिंग में शो जोड़ें, वे यहाँ दिखेंगे।"),
            color = TextMid,
            fontSize = 15.sp,
        )
        Spacer(Modifier.height(20.dp))
        GradientButton(t("Open settings", "सेटिंग खोलें"), icon = Ic.Add, onClick = onOpenSetup)
    }
}

@Composable
private fun PosterTile(vm: AppViewModel, tile: Tile, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val bmp = remember(tile.poster) { vm.poster(tile.poster) }
    val app = tile.steps.filterIsInstance<Step.LaunchApp>().firstOrNull()?.appName
    Box(
        Modifier
            .pressable(interaction)
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(28.dp))
            .background(Surface2)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), RoundedCornerShape(28.dp))
            .clickable(interaction, null, onClick = onClick),
    ) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), tile.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(Color(0xFF3A2A6B), Color(0xFF7A2E5C)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(tile.name.take(1).uppercase(), color = Color.White.copy(alpha = 0.9f), fontSize = 64.sp, fontWeight = FontWeight.Black)
            }
        }
        // Scrim so the title reads on any poster.
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))),
        )
        app?.let {
            Pill(
                it,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                background = Color.Black.copy(alpha = 0.5f),
                fontSize = 12.sp,
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                tile.name,
                color = Color.White,
                fontSize = 19.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AccentBrush),
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.Play, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        }
    }
}

/** The always-visible remote: big round buttons with labels, floating over the grid. */
@Composable
private fun RemoteDock(vm: AppViewModel, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(36.dp))
            .background(Surface1.copy(alpha = 0.96f))
            .border(BorderStroke(1.dp, Outline), RoundedCornerShape(36.dp))
            .padding(horizontal = 6.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        DockButton(Ic.Back, t("Back", "पीछे")) { vm.remote("BACK") }
        DockButton(Ic.VolumeDown, t("Quieter", "धीमा")) { vm.remote("VOLUME_DOWN") }
        DockButton(Ic.PlayPause, t("Pause", "रोको"), accent = true) { vm.remote("MEDIA_PLAY_PAUSE") }
        DockButton(Ic.VolumeUp, t("Louder", "तेज़")) { vm.remote("VOLUME_UP") }
        DockButton(Ic.Home, t("Stop", "बंद")) { vm.stop() }
        DockButton(Ic.Power, t("TV power", "TV बटन"), tint = Bad) { vm.remote("POWER") }
    }
}

@Composable
private fun DockButton(icon: ImageVector, label: String, accent: Boolean = false, tint: Color = TextHi, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
        CircleIconButton(icon, label, size = if (accent) 56.dp else 50.dp, accent = accent, tint = tint, onClick = onClick)
        Spacer(Modifier.height(6.dp))
        Text(label, color = TextMid, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 13.sp)
    }
}

@Composable
fun RunOverlay(vm: AppViewModel, state: RunState, onStop: () -> Unit, onClose: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable(remember { MutableInteractionSource() }, null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(22.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(32.dp))
                .background(Surface1)
                .border(BorderStroke(1.dp, Outline), RoundedCornerShape(32.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val bmp = remember(state.poster) { vm.poster(state.poster) }
            Box(
                Modifier
                    .size(132.dp, 176.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Surface3),
                contentAlignment = Alignment.Center,
            ) {
                bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                if (state.done) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (state.ok) Good else Bad),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(if (state.ok) Ic.Check else Ic.Close, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(state.title, color = TextHi, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                state.message,
                color = if (state.done && !state.ok) Bad else TextMid,
                fontSize = 18.sp,
                lineHeight = 25.sp,
                textAlign = TextAlign.Center,
            )
            state.detail?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = TextLow, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
            }
            Spacer(Modifier.height(22.dp))
            if (!state.done) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50)),
                    color = Accent,
                    trackColor = Surface3,
                    strokeCap = StrokeCap.Round,
                )
                Spacer(Modifier.height(22.dp))
                TonalButton(t("Stop", "रोको"), Modifier.fillMaxWidth(), icon = Ic.Close, onClick = onStop)
            } else {
                GradientButton(t("OK", "ठीक है"), Modifier.fillMaxWidth(), onClick = onClose)
            }
        }
    }
}

@Composable
private fun PinDialog(correct: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        shape = RoundedCornerShape(28.dp),
        icon = { Icon(Ic.Lock, null, tint = Accent) },
        title = { Text(t("Settings PIN", "सेटिंग PIN"), color = TextHi) },
        text = {
            Column {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8); wrong = false },
                    singleLine = true,
                    placeholder = { Text("••••") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    shape = RoundedCornerShape(16.dp),
                )
                if (wrong) Text(t("Wrong PIN", "गलत PIN"), color = Bad, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { if (pin == correct) onOk() else wrong = true }) { Text(t("Open", "खोलें")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("Cancel", "रद्द करें"), color = TextMid) } },
    )
}

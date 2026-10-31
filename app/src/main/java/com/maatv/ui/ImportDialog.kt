package com.maatv.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maatv.AppViewModel
import com.maatv.ImportPreview

/** Preview of a shared shows file: tick which shows to add. */
@Composable
fun ImportDialog(vm: AppViewModel, preview: ImportPreview, tvApps: Set<String>?) {
    val context = LocalContext.current
    if (preview.error != null) {
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            containerColor = Surface1,
            shape = RoundedCornerShape(28.dp),
            icon = { Icon(Ic.Close, null, tint = Bad) },
            title = { Text("Can't import", color = TextHi) },
            text = { Text(preview.error, color = TextMid) },
            confirmButton = { TextButton(onClick = vm::cancelImport) { Text("OK") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = vm::cancelImport,
        containerColor = Surface1,
        shape = RoundedCornerShape(28.dp),
        icon = { Icon(Ic.Add, null, tint = Accent) },
        title = { Text("Add shared shows", color = TextHi) },
        text = {
            Column {
                Text(
                    "Shows already on this phone are not ticked. You still connect this phone to its own TV.",
                    color = TextMid,
                    fontSize = 13.sp,
                )
                if (preview.unsupported.isNotEmpty()) {
                    Spacer(Modifier.size(6.dp))
                    Text(
                        "Needs a newer Maa TV: ${preview.unsupported.joinToString()}",
                        color = Warn,
                        fontSize = 13.sp,
                    )
                }
                Spacer(Modifier.size(10.dp))
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(preview.items) { i, item ->
                        val checked = i in preview.selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Surface2)
                                .clickable { vm.toggleImport(i) }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val bmp = remember(item) { item.poster?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
                            Box(
                                Modifier
                                    .size(40.dp, 54.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Surface3),
                            ) {
                                bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, color = TextHi, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2)
                                val pkg = item.steps.filterIsInstance<com.maatv.data.Step.LaunchApp>().firstOrNull()?.pkg
                                val sub = buildList {
                                    item.appName?.let { add(it) }
                                    if (item.name.lowercase() in preview.existing) add("already here")
                                }.joinToString(" · ")
                                if (sub.isNotEmpty()) Text(sub, color = TextLow, fontSize = 12.sp)
                                if (tvApps != null && pkg != null && pkg !in tvApps) {
                                    Text("${item.appName} is not installed on this TV", color = Bad, fontSize = 12.sp)
                                }
                                item.notes.forEach { Text(it, color = Warn, fontSize = 12.sp, lineHeight = 15.sp) }
                            }
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { vm.toggleImport(i) },
                                colors = CheckboxDefaults.colors(checkedColor = Accent),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = preview.selected.isNotEmpty(),
                onClick = {
                    val n = vm.confirmImport()
                    Toast.makeText(context, "Added $n show${if (n == 1) "" else "s"}", Toast.LENGTH_SHORT).show()
                },
            ) { Text("Add ${preview.selected.size}") }
        },
        dismissButton = { TextButton(onClick = vm::cancelImport) { Text("Cancel", color = TextMid) } },
    )
}

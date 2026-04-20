package com.propaint.app.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

data class CanvasPreset(
    val label: String,
    val width: Int,
    val height: Int,
    val category: String,
)

private val presets = listOf(
    // 印刷 (350dpi)
    CanvasPreset("A4 縦 (2894x4093)", 2894, 4093, "印刷 350dpi"),
    CanvasPreset("A4 横 (4093x2894)", 4093, 2894, "印刷 350dpi"),
    CanvasPreset("A5 縦 (2039x2894)", 2039, 2894, "印刷 350dpi"),
    CanvasPreset("A5 横 (2894x2039)", 2894, 2039, "印刷 350dpi"),
    CanvasPreset("B5 縦 (2508x3541)", 2508, 3541, "印刷 350dpi"),
    CanvasPreset("はがき (1378x2039)", 1378, 2039, "印刷 350dpi"),
    // 印刷 (300dpi)
    CanvasPreset("A4 縦 (2480x3508)", 2480, 3508, "印刷 300dpi"),
    CanvasPreset("A4 横 (3508x2480)", 3508, 2480, "印刷 300dpi"),
    CanvasPreset("A5 縦 (1748x2480)", 1748, 2480, "印刷 300dpi"),
    CanvasPreset("A5 横 (2480x1748)", 2480, 1748, "印刷 300dpi"),
    CanvasPreset("B5 縦 (2150x3035)", 2150, 3035, "印刷 300dpi"),
    CanvasPreset("はがき (1181x1748)", 1181, 1748, "印刷 300dpi"),
    // SNS
    CanvasPreset("Twitter ヘッダー (1500x500)", 1500, 500, "SNS"),
    CanvasPreset("Instagram (1080x1080)", 1080, 1080, "SNS"),
    CanvasPreset("YouTube サムネ (1280x720)", 1280, 720, "SNS"),
    // スクリーンサイズ
    CanvasPreset("HD (1920x1080)", 1920, 1080, "スクリーン"),
    CanvasPreset("2K (2560x1440)", 2560, 1440, "スクリーン"),
    CanvasPreset("4K (3840x2160)", 3840, 2160, "スクリーン"),
    CanvasPreset("iPad (2732x2048)", 2732, 2048, "スクリーン"),
    // 正方形
    CanvasPreset("1024 x 1024", 1024, 1024, "正方形"),
    CanvasPreset("2048 x 2048", 2048, 2048, "正方形"),
    CanvasPreset("4096 x 4096", 4096, 4096, "正方形"),
)

@Composable
fun NewCanvasDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, width: Int, height: Int) -> Unit,
    projectCount: Int = 0,
) {
    val defaultName = "無題 ${projectCount + 1}"
    var name by remember { mutableStateOf("") }
    var customWidth by remember { mutableStateOf("2048") }
    var customHeight by remember { mutableStateOf("2048") }
    var selectedPreset by remember { mutableStateOf<CanvasPreset?>(null) }
    var showCustom by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.widthIn(max = 420.dp).heightIn(max = 560.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF222222)),
            elevation = CardDefaults.cardElevation(12.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("新しいキャンバス", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))

                // 名前入力
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("作品名") },
                    placeholder = { Text(defaultName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF6CB4EE),
                        focusedLabelColor = Color(0xFF6CB4EE),
                        unfocusedLabelColor = Color(0xFF888888),
                        cursorColor = Color(0xFF6CB4EE),
                    ),
                )

                Spacer(Modifier.height(12.dp))

                // プリセット一覧
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    var lastCategory = ""
                    for (preset in presets) {
                        if (preset.category != lastCategory) {
                            lastCategory = preset.category
                            Spacer(Modifier.height(4.dp))
                            Text(preset.category, color = Color(0xFF6CB4EE), fontSize = 11.sp)
                        }
                        val isSel = selectedPreset == preset && !showCustom
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) Color(0xFF3A5A7C) else Color(0xFF2A2A2A))
                                .border(
                                    1.dp,
                                    if (isSel) Color(0xFF6CB4EE) else Color.Transparent,
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { selectedPreset = preset; showCustom = false }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(preset.label, color = Color.White, fontSize = 12.sp)
                        }
                    }
                    // カスタムサイズ
                    Spacer(Modifier.height(4.dp))
                    Text("カスタム", color = Color(0xFF6CB4EE), fontSize = 11.sp)
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (showCustom) Color(0xFF3A5A7C) else Color(0xFF2A2A2A))
                            .border(
                                1.dp,
                                if (showCustom) Color(0xFF6CB4EE) else Color.Transparent,
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { showCustom = true }
                            .padding(12.dp),
                    ) {
                        if (showCustom) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedTextField(
                                    value = customWidth,
                                    onValueChange = { customWidth = it.filter { c -> c.isDigit() }.take(5) },
                                    label = { Text("幅", fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF6CB4EE),
                                        focusedLabelColor = Color(0xFF6CB4EE),
                                        unfocusedLabelColor = Color(0xFF888888),
                                    ),
                                )
                                Text("x", color = Color(0xFF888888))
                                OutlinedTextField(
                                    value = customHeight,
                                    onValueChange = { customHeight = it.filter { c -> c.isDigit() }.take(5) },
                                    label = { Text("高さ", fontSize = 10.sp) },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFF6CB4EE),
                                        focusedLabelColor = Color(0xFF6CB4EE),
                                        unfocusedLabelColor = Color(0xFF888888),
                                    ),
                                )
                            }
                        } else {
                            Text("カスタムサイズ...", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ボタン
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("キャンセル", color = Color(0xFFAAAAAA))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val finalName = name.ifBlank { defaultName }
                            if (showCustom) {
                                val w = customWidth.toIntOrNull()?.coerceIn(64, 16384) ?: 2048
                                val h = customHeight.toIntOrNull()?.coerceIn(64, 16384) ?: 2048
                                onCreate(finalName, w, h)
                            } else {
                                val p = selectedPreset ?: presets[0]
                                onCreate(finalName, p.width, p.height)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A5A7C)),
                    ) {
                        Text("作成")
                    }
                }
            }
        }
    }
}

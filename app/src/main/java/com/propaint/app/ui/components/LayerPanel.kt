package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.engine.PixelOps
import com.propaint.app.viewmodel.PaintViewModel
import com.propaint.app.viewmodel.UiLayer

@Composable
fun LayerPanel(viewModel: PaintViewModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val layers by viewModel.layers.collectAsState()

    Card(
        modifier = modifier.width(270.dp).heightIn(max = 450.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE222222)),
        elevation = CardDefaults.cardElevation(8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("レイヤー", color = Color.White, fontSize = 14.sp)
                IconButton(onClick = { viewModel.addLayer() }, Modifier.size(32.dp)) {
                    Icon(Icons.Default.Add, "Add", tint = Color(0xFF6CB4EE))
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(layers.reversed()) { _, layer ->
                    LayerItem(layer, viewModel)
                }
            }
        }
    }
}

@Composable
private fun LayerItem(layer: UiLayer, vm: PaintViewModel) {
    val bg = if (layer.isActive) Color(0xFF3A5A7C) else Color(0xFF2A2A2A)
    val border = if (layer.isActive) Color(0xFF6CB4EE) else Color.Transparent
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg)
        .border(1.dp, border, RoundedCornerShape(8.dp)).clickable { vm.selectLayer(layer.id) }.padding(8.dp)) {

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.setLayerVisibility(layer.id, !layer.isVisible) }, Modifier.size(26.dp)) {
                Icon(if (layer.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    "Vis", tint = if (layer.isVisible) Color.White else Color(0xFF666666), modifier = Modifier.size(16.dp))
            }
            // クリッピング
            IconButton(onClick = { vm.setLayerClip(layer.id, !layer.isClipToBelow) }, Modifier.size(26.dp)) {
                Icon(Icons.Default.ContentCut, "Clip",
                    tint = if (layer.isClipToBelow) Color(0xFF6CB4EE) else Color(0xFF555555), modifier = Modifier.size(14.dp))
            }
            // ロック
            IconButton(onClick = { vm.setLayerLocked(layer.id, !layer.isLocked) }, Modifier.size(26.dp)) {
                Icon(if (layer.isLocked) Icons.Default.Lock else Icons.Default.LockOpen, "Lock",
                    tint = if (layer.isLocked) Color(0xFFFF6B6B) else Color(0xFF555555), modifier = Modifier.size(14.dp))
            }

            Text(layer.name, color = Color.White, fontSize = 12.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            Text("${(layer.opacity * 100).toInt()}%", color = Color(0xFFAAAAAA), fontSize = 10.sp)

            IconButton(onClick = { expanded = !expanded }, Modifier.size(26.dp)) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    "Expand", tint = Color(0xFF888888), modifier = Modifier.size(14.dp))
            }
        }

        if (expanded) {
            Spacer(Modifier.height(4.dp))
            // 不透明度
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("不透明度", color = Color(0xFFAAAAAA), fontSize = 10.sp, modifier = Modifier.width(50.dp))
                Slider(value = layer.opacity, onValueChange = { vm.setLayerOpacity(layer.id, it) },
                    valueRange = 0f..1f, modifier = Modifier.weight(1f).height(24.dp),
                    colors = SliderDefaults.colors(thumbColor = Color.White,
                        activeTrackColor = Color(0xFF6CB4EE), inactiveTrackColor = Color(0xFF444444)))
            }

            // ブレンドモード
            var showMenu by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("合成", color = Color(0xFFAAAAAA), fontSize = 10.sp, modifier = Modifier.width(50.dp))
                Box {
                    TextButton(onClick = { showMenu = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                        val name = if (layer.blendMode < PixelOps.BLEND_MODE_NAMES.size)
                            PixelOps.BLEND_MODE_NAMES[layer.blendMode] else "通常"
                        Text(name, color = Color.White, fontSize = 11.sp)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        for (mode in listOf(
                            PixelOps.BLEND_NORMAL,
                            PixelOps.BLEND_MULTIPLY, PixelOps.BLEND_SCREEN,
                            PixelOps.BLEND_OVERLAY, PixelOps.BLEND_DARKEN,
                            PixelOps.BLEND_LIGHTEN, PixelOps.BLEND_COLOR_DODGE,
                            PixelOps.BLEND_COLOR_BURN, PixelOps.BLEND_HARD_LIGHT,
                            PixelOps.BLEND_SOFT_LIGHT, PixelOps.BLEND_DIFFERENCE,
                            PixelOps.BLEND_EXCLUSION, PixelOps.BLEND_ADD,
                            PixelOps.BLEND_SUBTRACT, PixelOps.BLEND_LINEAR_BURN,
                            PixelOps.BLEND_LINEAR_LIGHT, PixelOps.BLEND_VIVID_LIGHT,
                            PixelOps.BLEND_PIN_LIGHT,
                            PixelOps.BLEND_HUE, PixelOps.BLEND_SATURATION,
                            PixelOps.BLEND_COLOR, PixelOps.BLEND_LUMINOSITY,
                        )) {
                            DropdownMenuItem(
                                text = { Text(PixelOps.BLEND_MODE_NAMES[mode], fontSize = 12.sp) },
                                onClick = { vm.setLayerBlendMode(layer.id, mode); showMenu = false })
                        }
                    }
                }
            }

            // レイヤー移動ボタン
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = { vm.moveLayerUp(layer.id) }, Modifier.size(26.dp)) {
                    Icon(Icons.Default.ArrowUpward, "上へ", tint = Color(0xFF6CB4EE), modifier = Modifier.size(14.dp))
                }
                IconButton(onClick = { vm.moveLayerDown(layer.id) }, Modifier.size(26.dp)) {
                    Icon(Icons.Default.ArrowDownward, "下へ", tint = Color(0xFF6CB4EE), modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.weight(1f))
                // コンテンツ移動ボタン (10px 単位)
                Text("移動:", color = Color(0xFF888888), fontSize = 9.sp)
                IconButton(onClick = { vm.translateLayerContent(layer.id, -10, 0) }, Modifier.size(22.dp)) {
                    Icon(Icons.Default.ArrowBack, "←", tint = Color(0xFFAAAAAA), modifier = Modifier.size(12.dp))
                }
                IconButton(onClick = { vm.translateLayerContent(layer.id, 10, 0) }, Modifier.size(22.dp)) {
                    Icon(Icons.Default.ArrowForward, "→", tint = Color(0xFFAAAAAA), modifier = Modifier.size(12.dp))
                }
                IconButton(onClick = { vm.translateLayerContent(layer.id, 0, -10) }, Modifier.size(22.dp)) {
                    Icon(Icons.Default.ArrowUpward, "↑", tint = Color(0xFFAAAAAA), modifier = Modifier.size(12.dp))
                }
                IconButton(onClick = { vm.translateLayerContent(layer.id, 0, 10) }, Modifier.size(22.dp)) {
                    Icon(Icons.Default.ArrowDownward, "↓", tint = Color(0xFFAAAAAA), modifier = Modifier.size(12.dp))
                }
            }

            // アクションボタン
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.duplicateLayer(layer.id) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                    Text("複製", fontSize = 10.sp, color = Color(0xFF6CB4EE))
                }
                TextButton(onClick = { vm.mergeDown(layer.id) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                    Text("下に結合", fontSize = 10.sp, color = Color(0xFF6CB4EE))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.removeLayer(layer.id) }, Modifier.size(26.dp)) {
                    Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFFF6B6B), modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

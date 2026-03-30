package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.viewmodel.BrushType
import com.propaint.app.viewmodel.PaintViewModel

@Composable
fun BrushPanel(viewModel: PaintViewModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val type by viewModel.currentBrushType.collectAsState()
    val size by viewModel.brushSize.collectAsState()
    val opacity by viewModel.brushOpacity.collectAsState()
    val hardness by viewModel.brushHardness.collectAsState()
    val density by viewModel.brushDensity.collectAsState()
    val spacing by viewModel.brushSpacing.collectAsState()
    val stabilizer by viewModel.brushStabilizer.collectAsState()
    val blurPressureThreshold by viewModel.blurPressureThreshold.collectAsState()
    val pressureSize by viewModel.pressureSizeEnabled.collectAsState()
    val pressureOpacity by viewModel.pressureOpacityEnabled.collectAsState()
    val pressureDensity by viewModel.pressureDensityEnabled.collectAsState()

    Card(
        modifier = modifier.width(290.dp).heightIn(max = 500.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE222222)),
        elevation = CardDefaults.cardElevation(8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("ブラシ設定", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))

            // ブラシ種別
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (row in BrushType.entries.chunked(4)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (t in row) {
                            val sel = t == type
                            Box(modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(if (sel) Color(0xFF3A5A7C) else Color(0xFF333333))
                                .border(1.dp, if (sel) Color(0xFF6CB4EE) else Color.Transparent, RoundedCornerShape(6.dp))
                                .clickable { viewModel.setBrushType(t) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center) {
                                Text(t.displayName, color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 共通パラメータ
            ParamSlider("サイズ", "${size.toInt()}px", size, 1f..500f) { viewModel.setBrushSize(it) }
            if (type.supportsOpacity) {
                ParamSlider("不透明度", "${(opacity * 100).toInt()}%", opacity, 0.01f..1f) { viewModel.setBrushOpacity(it) }
            }
            ParamSlider("硬さ", "${(hardness * 100).toInt()}%", hardness, 0f..1f) { viewModel.setBrushHardness(it) }
            if (type.supportsDensity) {
                ParamSlider("濃度", "${(density * 100).toInt()}%", density, 0.01f..1f) { viewModel.setBrushDensity(it) }
            }
            ParamSlider("間隔", "${(spacing * 100).toInt()}%", spacing, 0.01f..2f) { viewModel.setBrushSpacing(it) }
            ParamSlider("手振れ補正", "${(stabilizer * 100).toInt()}%", stabilizer, 0f..1f) { viewModel.setStabilizer(it) }

            // ぼかし筆圧 (筆/水彩のみ)
            if (type == BrushType.Fude || type == BrushType.Watercolor) {
                ParamSlider("ぼかし筆圧", "${(blurPressureThreshold * 100).toInt()}%",
                    blurPressureThreshold, 0f..1f) { viewModel.setBlurPressureThreshold(it) }
            }

            // 筆圧
            Spacer(Modifier.height(8.dp))
            Text("筆圧", color = Color(0xFF6CB4EE), fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = pressureSize, onCheckedChange = { viewModel.togglePressureSize() },
                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF6CB4EE)))
                Text("サイズ", color = Color.White, fontSize = 11.sp)
                Spacer(Modifier.width(12.dp))
                if (type.supportsOpacity) {
                    Checkbox(checked = pressureOpacity, onCheckedChange = { viewModel.togglePressureOpacity() },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF6CB4EE)))
                    Text("不透明度", color = Color.White, fontSize = 11.sp)
                    Spacer(Modifier.width(12.dp))
                }
                if (type.supportsDensity) {
                    Checkbox(checked = pressureDensity, onCheckedChange = { viewModel.togglePressureDensity() },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFF6CB4EE)))
                    Text("濃度", color = Color.White, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { viewModel.clearActiveLayer() },
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF6B6B))) {
                Text("レイヤーをクリア", fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun ParamSlider(label: String, valueText: String, value: Float,
                         range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFFAAAAAA), fontSize = 11.sp)
            Text(valueText, color = Color.White, fontSize = 11.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range,
            modifier = Modifier.fillMaxWidth().height(28.dp),
            colors = SliderDefaults.colors(thumbColor = Color.White,
                activeTrackColor = Color(0xFF6CB4EE), inactiveTrackColor = Color(0xFF444444)))
    }
}

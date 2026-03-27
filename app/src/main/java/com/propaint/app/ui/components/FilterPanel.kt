package com.propaint.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.engine.CanvasDocument
import com.propaint.app.viewmodel.PaintViewModel
import kotlinx.coroutines.delay

@Composable
fun FilterPanel(viewModel: PaintViewModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    var filterType by remember { mutableIntStateOf(0) } // 0=HSL, 1=Contrast, 2=Blur
    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(0f) }
    var lit by remember { mutableFloatStateOf(0f) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(0f) }
    var blurRadius by remember { mutableFloatStateOf(5f) }
    var blurType by remember { mutableIntStateOf(CanvasDocument.BLUR_GAUSSIAN) }
    var blurPreviewActive by remember { mutableStateOf(false) }

    // ブラータブに切り替わったらプレビュー開始、離れたらキャンセル
    DisposableEffect(filterType) {
        if (filterType == 2) {
            viewModel.beginBlurPreview()
            blurPreviewActive = true
        } else if (blurPreviewActive) {
            viewModel.cancelBlurPreview()
            blurPreviewActive = false
        }
        onDispose {
            if (blurPreviewActive) {
                viewModel.cancelBlurPreview()
            }
        }
    }

    // スライダー変更をデバウンスしてリアルタイムプレビュー
    LaunchedEffect(blurRadius, blurType) {
        if (filterType != 2 || !blurPreviewActive) return@LaunchedEffect
        delay(80) // 80ms デバウンス
        viewModel.updateBlurPreview(blurRadius.toInt(), blurType)
    }

    Card(
        modifier = modifier.width(320.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE222222)),
        elevation = CardDefaults.cardElevation(8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("フィルター", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filterType == 0, onClick = { filterType = 0 },
                    label = { Text("HSL", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF3A5A7C)))
                FilterChip(selected = filterType == 1, onClick = { filterType = 1 },
                    label = { Text("明るさ/コントラスト", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF3A5A7C)))
                FilterChip(selected = filterType == 2, onClick = { filterType = 2 },
                    label = { Text("ぼかし", fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF3A5A7C)))
            }

            Spacer(Modifier.height(12.dp))

            when (filterType) {
                0 -> {
                    ParamSlider("色相", "${hue.toInt()}°", hue, -180f..180f) { hue = it }
                    ParamSlider("彩度", "${(sat * 100).toInt()}%", sat, -1f..1f) { sat = it }
                    ParamSlider("明度", "${(lit * 100).toInt()}%", lit, -1f..1f) { lit = it }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            viewModel.applyHslFilter(hue, sat, lit)
                            hue = 0f; sat = 0f; lit = 0f
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A5A7C))) {
                            Text("適用", fontSize = 12.sp)
                        }
                        TextButton(onClick = { hue = 0f; sat = 0f; lit = 0f }) {
                            Text("リセット", fontSize = 12.sp, color = Color(0xFFAAAAAA))
                        }
                    }
                }
                1 -> {
                    ParamSlider("明るさ", "${(brightness * 100).toInt()}%", brightness, -1f..1f) { brightness = it }
                    ParamSlider("コントラスト", "${(contrast * 100).toInt()}%", contrast, -1f..1f) { contrast = it }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            viewModel.applyBrightnessContrast(brightness, contrast)
                            brightness = 0f; contrast = 0f
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A5A7C))) {
                            Text("適用", fontSize = 12.sp)
                        }
                        TextButton(onClick = { brightness = 0f; contrast = 0f }) {
                            Text("リセット", fontSize = 12.sp, color = Color(0xFFAAAAAA))
                        }
                    }
                }
                2 -> {
                    // ブラー種別選択
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = blurType == CanvasDocument.BLUR_GAUSSIAN,
                            onClick = { blurType = CanvasDocument.BLUR_GAUSSIAN },
                            label = { Text("ガウスブラー", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF3A5A7C)),
                        )
                        FilterChip(
                            selected = blurType == CanvasDocument.BLUR_STACK,
                            onClick = { blurType = CanvasDocument.BLUR_STACK },
                            label = { Text("スタックブラー", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF3A5A7C)),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    ParamSlider("半径", "${blurRadius.toInt()}px", blurRadius, 1f..100f) { blurRadius = it }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            viewModel.commitBlurPreview()
                            blurPreviewActive = false
                            // 適用後に新しいプレビューセッションを開始
                            viewModel.beginBlurPreview()
                            blurPreviewActive = true
                            blurRadius = 5f
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A5A7C))) {
                            Text("適用", fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            viewModel.cancelBlurPreview()
                            blurPreviewActive = false
                            blurRadius = 5f
                            // リセット後に新しいプレビューセッションを開始
                            viewModel.beginBlurPreview()
                            blurPreviewActive = true
                        }) {
                            Text("リセット", fontSize = 12.sp, color = Color(0xFFAAAAAA))
                        }
                    }
                }
            }
        }
    }
}

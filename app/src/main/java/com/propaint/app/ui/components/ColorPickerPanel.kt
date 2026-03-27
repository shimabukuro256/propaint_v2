package com.propaint.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.viewmodel.PaintViewModel

@Composable
fun ColorPickerPanel(viewModel: PaintViewModel, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val currentColor by viewModel.currentColor.collectAsState()
    val history by viewModel.colorHistory.collectAsState()

    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(0f) }
    var value by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(currentColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV(
            (currentColor.red * 255).toInt(), (currentColor.green * 255).toInt(),
            (currentColor.blue * 255).toInt(), hsv)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
    }

    fun update(h: Float, s: Float, v: Float) {
        hue = h; sat = s; value = v
        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
        viewModel.setColor(Color(((rgb shr 16) and 0xFF) / 255f,
            ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f))
    }

    Card(modifier = modifier.width(300.dp), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xEE222222)),
        elevation = CardDefaults.cardElevation(8.dp)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("カラーピッカー", color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))

            // SV 矩形
            Box(Modifier.size(220.dp, 170.dp).clip(RoundedCornerShape(4.dp))) {
                Canvas(Modifier.fillMaxSize()
                    .pointerInput(hue) { detectTapGestures { o ->
                        update(hue, (o.x / size.width).coerceIn(0f, 1f), 1f - (o.y / size.height).coerceIn(0f, 1f))
                    }}
                    .pointerInput(hue) { detectDragGestures { c, _ -> c.consume()
                        update(hue, (c.position.x / size.width).coerceIn(0f, 1f),
                            1f - (c.position.y / size.height).coerceIn(0f, 1f))
                    }}
                ) {
                    val w = size.width; val h = size.height; val step = 4f
                    var x = 0f
                    while (x < w) { var y = 0f
                        while (y < h) {
                            val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, x / w, 1f - y / h))
                            drawRect(Color(rgb), Offset(x, y), Size(step + 1f, step + 1f)); y += step
                        }; x += step
                    }
                    val cx2 = sat * w; val cy2 = (1f - value) * h
                    drawCircle(Color.White, 7f, Offset(cx2, cy2), style = Stroke(2f))
                    drawCircle(Color.Black, 5f, Offset(cx2, cy2), style = Stroke(1f))
                }
            }

            Spacer(Modifier.height(10.dp))

            // Hue スライダー
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("H", color = Color(0xFFAAAAAA), fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f).height(22.dp)) {
                    Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))
                        .pointerInput(Unit) { detectTapGestures { o -> update(o.x / size.width * 360f, sat, value) }}
                        .pointerInput(Unit) { detectDragGestures { c, _ -> c.consume()
                            update((c.position.x / size.width * 360f).coerceIn(0f, 360f), sat, value)
                        }}
                    ) {
                        val w = size.width; val h = size.height; val step = 2f; var x = 0f
                        while (x < w) {
                            val rgb = android.graphics.Color.HSVToColor(floatArrayOf(x / w * 360f, 1f, 1f))
                            drawRect(Color(rgb), Offset(x, 0f), Size(step + 1f, h)); x += step
                        }
                        drawRect(Color.White, Offset(hue / 360f * w - 1.5f, 0f), Size(3f, h))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text("${hue.toInt()}°", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(30.dp))
            }

            Spacer(Modifier.height(10.dp))
            Text("カラー履歴", color = Color(0xFFAAAAAA), fontSize = 11.sp, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(4.dp))
            LazyVerticalGrid(columns = GridCells.Fixed(8), Modifier.heightIn(max = 72.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(history) { c ->
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(3.dp)).background(c)
                        .border(if (c == currentColor) 2.dp else 1.dp,
                            if (c == currentColor) Color.White else Color(0xFF444444),
                            RoundedCornerShape(3.dp))
                        .clickable { viewModel.setColor(c) })
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)).background(currentColor)
                    .border(1.dp, Color.White, RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(8.dp))
                val r = (currentColor.red * 255).toInt(); val g = (currentColor.green * 255).toInt(); val b = (currentColor.blue * 255).toInt()
                Text(String.format("#%02X%02X%02X", r, g, b), color = Color(0xFFAAAAAA), fontSize = 11.sp)
            }
        }
    }
}

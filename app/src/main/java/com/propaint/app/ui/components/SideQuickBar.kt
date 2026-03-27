package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.viewmodel.PaintViewModel

@Composable
fun SideQuickBar(viewModel: PaintViewModel, modifier: Modifier = Modifier) {
    val size by viewModel.brushSize.collectAsState()
    val opacity by viewModel.brushOpacity.collectAsState()

    Column(
        modifier = modifier.width(40.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xAA1A1A1A)).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${size.toInt()}", color = Color.White, fontSize = 10.sp)
        VerticalSlider(size, { viewModel.setBrushSize(it) }, 1f..500f, Modifier.height(120.dp))
        Spacer(Modifier.height(8.dp))
        Text("${(opacity * 100).toInt()}%", color = Color.White, fontSize = 10.sp)
        VerticalSlider(opacity, { viewModel.setBrushOpacity(it) }, 0.01f..1f, Modifier.height(120.dp))
    }
}

@Composable
private fun VerticalSlider(value: Float, onChange: (Float) -> Unit,
                           range: ClosedFloatingPointRange<Float>, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Slider(value = value, onValueChange = onChange, valueRange = range,
            modifier = Modifier.width(120.dp).rotate(-90f).layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                layout(p.height, p.width) { p.place(-p.width / 2 + p.height / 2, p.width / 2 - p.height / 2) }
            },
            colors = SliderDefaults.colors(thumbColor = Color.White,
                activeTrackColor = Color(0xFF6CB4EE), inactiveTrackColor = Color(0xFF444444)))
    }
}

package com.propaint.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import com.propaint.app.viewmodel.PaintViewModel

@Composable
fun Toolbar(
    viewModel: PaintViewModel,
    onBrushClick: () -> Unit,
    onColorClick: () -> Unit,
    onLayerClick: () -> Unit,
    onFilterClick: () -> Unit,
    onFileMenuClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val brushType by viewModel.currentBrushType.collectAsState()
    val currentColor by viewModel.currentColor.collectAsState()

    Row(
        modifier = modifier.fillMaxWidth().height(52.dp)
            .background(Color(0xCC1A1A1A)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.padding(start = 36.dp)) {
            IconButton(onClick = { viewModel.undo() }, enabled = canUndo) {
                Icon(Icons.Default.Undo, "Undo", tint = if (canUndo) Color.White else Color.Gray)
            }
            IconButton(onClick = { viewModel.redo() }, enabled = canRedo) {
                Icon(Icons.Default.Redo, "Redo", tint = if (canRedo) Color.White else Color.Gray)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBrushClick,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                Icon(Icons.Default.Brush, "Brush", Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(brushType.displayName, fontSize = 12.sp)
            }
            Box(modifier = Modifier.size(30.dp).clip(CircleShape).background(currentColor)
                .border(2.dp, Color.White, CircleShape).clickable { onColorClick() })
            IconButton(onClick = { viewModel.activateEyedropper() }, Modifier.size(36.dp)) {
                Icon(Icons.Default.Colorize, "Eyedropper", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            IconButton(onClick = onFileMenuClick) {
                Icon(Icons.Default.FolderOpen, "ファイル", tint = Color.White)
            }
            IconButton(onClick = onFilterClick) {
                Icon(Icons.Default.Tune, "Filter", tint = Color.White)
            }
            IconButton(onClick = { viewModel.resetView() }) {
                Icon(Icons.Default.CenterFocusStrong, "Reset", tint = Color.White)
            }
            IconButton(onClick = onLayerClick) {
                Icon(Icons.Default.Layers, "Layers", tint = Color.White)
            }
        }
    }
}

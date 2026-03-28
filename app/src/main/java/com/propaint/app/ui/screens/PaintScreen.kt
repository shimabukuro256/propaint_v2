package com.propaint.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propaint.app.gl.GlCanvasView
import com.propaint.app.ui.components.*
import com.propaint.app.viewmodel.PaintViewModel
import com.propaint.app.viewmodel.ToolMode

@Composable
fun PaintScreen(viewModel: PaintViewModel, onBack: () -> Unit) {

    var showBrushPanel by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showLayerPanel by remember { mutableStateOf(false) }
    var showFilterPanel by remember { mutableStateOf(false) }
    var showFileMenu by remember { mutableStateOf(false) }
    val toolMode by viewModel.toolMode.collectAsState()
    val context = LocalContext.current

    // ── ファイルピッカー ──

    // 画像インポート (PNG/JPEG/WebP をレイヤーとして)
    val importImageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "インポート"
                val success = viewModel.importImageAsLayer(stream, fileName)
                if (!success) Toast.makeText(context, "画像の読み込みに失敗しました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "エラー: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // PSD インポート
    val importPsdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val success = viewModel.importPsd(stream)
                if (!success) Toast.makeText(context, "PSDの読み込みに失敗しました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "エラー: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // プロジェクトインポート
    val importProjectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val success = viewModel.loadProject(stream)
                if (!success) Toast.makeText(context, "プロジェクトの読み込みに失敗しました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "エラー: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // PNG エクスポート
    val exportPngLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                viewModel.exportPng(stream)
                Toast.makeText(context, "PNGを保存しました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "エラー: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // JPEG エクスポート
    val exportJpegLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/jpeg")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                viewModel.exportJpeg(stream)
                Toast.makeText(context, "JPEGを保存しました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "エラー: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // WebP エクスポート
    val exportWebpLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/webp")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                viewModel.exportWebp(stream)
                Toast.makeText(context, "WebPを保存しました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "エラー: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // PSD エクスポート
    val exportPsdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/vnd.adobe.photoshop")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                viewModel.exportPsd(stream)
                Toast.makeText(context, "PSDを保存しました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "エラー: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // プロジェクトエクスポート
    val exportProjectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                viewModel.saveProject(stream)
                Toast.makeText(context, "プロジェクトを保存しました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "エラー: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun closeAllPanels() {
        showBrushPanel = false; showColorPicker = false
        showLayerPanel = false; showFilterPanel = false; showFileMenu = false
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF2A2A2A))) {
        GlCanvasView(
            renderer = viewModel.renderer,
            onTouch = { viewModel.onTouchEvent(it) },
            onHover = { viewModel.onHoverEvent(it) },
            modifier = Modifier.fillMaxSize(),
        )

        // ブラシサイズカーソル
        val cursor by viewModel.cursorState.collectAsState()
        if (cursor.visible && cursor.radius > 0.5f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(cursor.x, cursor.y)
                val r = cursor.radius
                drawCircle(Color.Black, radius = r, center = center,
                    style = Stroke(width = 2.5f))
                drawCircle(Color.White, radius = r, center = center,
                    style = Stroke(width = 1.2f))
            }
        }

        // スポイトモード表示
        if (toolMode == ToolMode.Eyedropper) {
            Box(modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp)
                .background(Color(0xCC333333), shape = RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("スポイト: キャンバスをタップ", color = Color.White, fontSize = 13.sp)
            }
        }

        Toolbar(
            viewModel = viewModel,
            onBrushClick = { showBrushPanel = !showBrushPanel; showColorPicker = false; showLayerPanel = false; showFilterPanel = false; showFileMenu = false },
            onColorClick = { showColorPicker = !showColorPicker; showBrushPanel = false; showLayerPanel = false; showFilterPanel = false; showFileMenu = false },
            onLayerClick = { showLayerPanel = !showLayerPanel; showBrushPanel = false; showColorPicker = false; showFilterPanel = false; showFileMenu = false },
            onFilterClick = { showFilterPanel = !showFilterPanel; showBrushPanel = false; showColorPicker = false; showLayerPanel = false; showFileMenu = false },
            onFileMenuClick = { showFileMenu = !showFileMenu; showBrushPanel = false; showColorPicker = false; showLayerPanel = false; showFilterPanel = false },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // ギャラリーに戻るボタン
        IconButton(
            onClick = {
                viewModel.closeProject()
                onBack()
            },
            modifier = Modifier.align(Alignment.TopStart).padding(top = 4.dp, start = 2.dp)
                .size(44.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "ギャラリーに戻る",
                tint = Color.White)
        }

        SideQuickBar(viewModel = viewModel,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp, top = 60.dp))

        if (showBrushPanel) BrushPanel(viewModel, { showBrushPanel = false },
            Modifier.align(Alignment.TopStart).padding(top = 56.dp, start = 8.dp))
        if (showColorPicker) ColorPickerPanel(viewModel, { showColorPicker = false },
            Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
        if (showLayerPanel) LayerPanel(viewModel, { showLayerPanel = false },
            Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 8.dp))
        if (showFilterPanel) FilterPanel(viewModel, { showFilterPanel = false },
            Modifier.align(Alignment.TopCenter).padding(top = 56.dp))
        if (showFileMenu) FileMenu(
            onDismiss = { showFileMenu = false },
            onImportImage = { importImageLauncher.launch(arrayOf("image/png", "image/jpeg", "image/webp")) },
            onImportPsd = { importPsdLauncher.launch(arrayOf("application/octet-stream", "image/vnd.adobe.photoshop", "*/*")) },
            onImportProject = { importProjectLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
            onExportPng = { exportPngLauncher.launch("painting.png") },
            onExportJpeg = { exportJpegLauncher.launch("painting.jpg") },
            onExportWebp = { exportWebpLauncher.launch("painting.webp") },
            onExportPsd = { exportPsdLauncher.launch("painting.psd") },
            onExportProject = { exportProjectLauncher.launch("painting.ppaint") },
            onSave = { viewModel.saveCurrentProject() },
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 60.dp),
        )
    }
}

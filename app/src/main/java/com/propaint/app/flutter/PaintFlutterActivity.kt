package com.propaint.app.flutter

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import com.propaint.app.engine.MemoryConfig
import com.propaint.app.engine.PaintDebug
import com.propaint.app.viewmodel.PaintViewModel
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Flutter UI をホストする Activity。
 */
class PaintFlutterActivity : FlutterActivity(), ViewModelStoreOwner {

    private val vmStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = vmStore

    private lateinit var viewModel: PaintViewModel
    private var channelHandler: PaintMethodChannelHandler? = null

    // エクスポート種別を onActivityResult で区別するためのリクエストコード
    // Activity 再生成時も SavedInstanceState で保持する
    private var pendingExportFormat: String? = null

    /** onPause で保存済みなら onStop での重複保存を抑制 */
    private var savedOnPause = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Activity 再生成時に pendingExportFormat を復元
        pendingExportFormat = savedInstanceState?.getString(KEY_PENDING_FORMAT)

        // デバイス RAM に基づいてメモリ設定を初期化
        MemoryConfig.init(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_FORMAT, pendingExportFormat)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        viewModel = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[PaintViewModel::class.java]

        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        if (projectId != null) {
            PaintDebug.d(PaintDebug.Layer) { "[FlutterActivity] openProject id=$projectId" }
            viewModel.openProject(projectId)
        } else {
            PaintDebug.d(PaintDebug.Layer) { "[FlutterActivity] initCanvas 2048x2048 (no project_id)" }
            viewModel.initCanvas(2048, 2048)
        }

        flutterEngine.platformViewsController
            .registry
            .registerViewFactory(VIEW_TYPE, PaintCanvasViewFactory(viewModel))

        channelHandler = PaintMethodChannelHandler(
            flutterEngine.dartExecutor.binaryMessenger,
            viewModel,
        )

        // エクスポート/インポート要求コールバック
        viewModel.onExportRequest = { format ->
            runOnUiThread { launchExport(format) }
        }
        viewModel.onImportRequest = { type ->
            runOnUiThread { launchImport(type) }
        }
        // ギャラリーに戻る（保存は非同期で完了後に finish）
        viewModel.onReturnToGallery = {
            lifecycleScope.launch(Dispatchers.IO) {
                viewModel.saveCurrentProject()
                withContext(Dispatchers.Main) { finish() }
            }
        }
    }

    private fun launchExport(format: String) {
        pendingExportFormat = format
        val mimeType = when (format) {
            "png" -> "image/png"
            "jpeg" -> "image/jpeg"
            "psd" -> "application/octet-stream"
            "project" -> "application/octet-stream"
            else -> "application/octet-stream"
        }
        val ext = when (format) {
            "png" -> "artwork.png"
            "jpeg" -> "artwork.jpg"
            "psd" -> "artwork.psd"
            "project" -> "artwork.ppaint"
            else -> "artwork.bin"
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, ext)
        }
        startActivityForResult(intent, RC_EXPORT)
    }

    private fun launchImport(type: String) {
        pendingExportFormat = type // reuse field for import type
        val mimeType = when (type) {
            "image" -> "image/*"
            "psd" -> "*/*"       // PSD は image/vnd.adobe.photoshop だが端末によっては未対応
            "project" -> "*/*"
            else -> "*/*"
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = mimeType
        }
        startActivityForResult(intent, RC_IMPORT)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            RC_EXPORT -> handleExport(uri)
            RC_IMPORT -> handleImport(uri)
        }
    }

    private fun handleExport(uri: Uri) {
        val format = pendingExportFormat ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                when (format) {
                    "png" -> viewModel.exportPng(os)
                    "jpeg" -> viewModel.exportJpeg(os)
                    "psd" -> viewModel.exportPsd(os)
                    "project" -> viewModel.saveProject(os)
                }
            }
            val label = when (format) {
                "png" -> "PNG"
                "jpeg" -> "JPEG"
                "psd" -> "PSD"
                "project" -> "プロジェクト"
                else -> format
            }
            Toast.makeText(this, "$label を保存しました", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            PaintDebug.d(PaintDebug.Layer) { "[Export] $format error: ${e.message}" }
            Toast.makeText(this, "保存に失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleImport(uri: Uri) {
        val type = pendingExportFormat ?: return
        try {
            val success = contentResolver.openInputStream(uri)?.use { ins ->
                when (type) {
                    "image" -> viewModel.importImageAsLayer(ins)
                    "psd" -> viewModel.importPsd(ins)
                    "project" -> viewModel.loadProject(ins)
                    else -> false
                }
            } ?: false
            val label = when (type) {
                "image" -> "画像をレイヤーとしてインポート"
                "psd" -> "PSD をインポート"
                "project" -> "プロジェクトを読み込み"
                else -> "インポート"
            }
            if (success) {
                Toast.makeText(this, "${label}しました", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "${label}に失敗しました", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "読み込みに失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        channelHandler?.dispose()
        channelHandler = null
        viewModel.onExportRequest = null
        viewModel.onImportRequest = null
        viewModel.onReturnToGallery = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun onResume() {
        super.onResume()
        // onResume で savedOnPause フラグをリセット
        savedOnPause = false
    }

    override fun onPause() {
        super.onPause()
        // 非同期で保存（UI スレッドをブロックしない → ANR 防止）
        savedOnPause = true
        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.saveCurrentProject()
            PaintDebug.d(PaintDebug.Perf) { "[Lifecycle] onPause save completed" }
        }
    }

    override fun onStop() {
        super.onStop()
        // onPause で既に保存を開始済みなら重複しない
        if (!savedOnPause) {
            lifecycleScope.launch(Dispatchers.IO) {
                viewModel.saveCurrentProject()
                PaintDebug.d(PaintDebug.Perf) { "[Lifecycle] onStop save completed" }
            }
        }
    }

    companion object {
        const val EXTRA_PROJECT_ID = "project_id"
        const val VIEW_TYPE = "paint-gl-canvas"
        private const val RC_EXPORT = 1001
        private const val RC_IMPORT = 1002
        private const val KEY_PENDING_FORMAT = "pending_export_format"
    }
}

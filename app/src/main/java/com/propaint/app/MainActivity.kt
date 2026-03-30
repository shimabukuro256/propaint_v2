package com.propaint.app

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.propaint.app.flutter.PaintFlutterActivity
import com.propaint.app.gallery.GalleryScreen
import com.propaint.app.ui.screens.PaintScreen
import com.propaint.app.ui.theme.ProPaintTheme
import com.propaint.app.viewmodel.PaintViewModel

class MainActivity : ComponentActivity() {

    /** true にすると Flutter UI、false で従来の Compose UI を使用 */
    private val useFlutterUi = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // イマーシブモード
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContent {
            ProPaintTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val viewModel: PaintViewModel = viewModel()
                    var screen by remember { mutableStateOf<Screen>(Screen.Gallery) }

                    when (screen) {
                        Screen.Gallery -> {
                            GalleryScreen(
                                onOpenProject = { id ->
                                    if (useFlutterUi) {
                                        launchFlutterPaint(id)
                                    } else {
                                        viewModel.openProject(id)
                                        screen = Screen.Paint
                                    }
                                },
                                onNewCanvas = { name, w, h ->
                                    if (useFlutterUi) {
                                        // 新規プロジェクトを作成し Flutter で開く
                                        val projectId = viewModel.galleryRepo
                                            .createProject(name, w, h)
                                        launchFlutterPaint(projectId)
                                    } else {
                                        viewModel.openNewProject(name, w, h)
                                        screen = Screen.Paint
                                    }
                                },
                            )
                        }
                        Screen.Paint -> {
                            PaintScreen(
                                viewModel = viewModel,
                                onBack = { screen = Screen.Gallery },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun launchFlutterPaint(projectId: String) {
        val intent = Intent(this, PaintFlutterActivity::class.java).apply {
            putExtra(PaintFlutterActivity.EXTRA_PROJECT_ID, projectId)
        }
        startActivity(intent)
    }
}

private enum class Screen { Gallery, Paint }

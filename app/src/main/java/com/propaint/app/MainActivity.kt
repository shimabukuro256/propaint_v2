package com.propaint.app

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
import com.propaint.app.gallery.GalleryScreen
import com.propaint.app.ui.screens.PaintScreen
import com.propaint.app.ui.theme.ProPaintTheme
import com.propaint.app.viewmodel.PaintViewModel

class MainActivity : ComponentActivity() {
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
                                    viewModel.openProject(id)
                                    screen = Screen.Paint
                                },
                                onNewCanvas = { name, w, h ->
                                    viewModel.openNewProject(name, w, h)
                                    screen = Screen.Paint
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
}

private enum class Screen { Gallery, Paint }

package com.propaint.app.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * GLSurfaceView の Compose ラッパー。
 * タッチ/スタイラス入力を受け取り、コールバックに委譲する。
 */
class PaintGlSurfaceView(
    context: Context,
    val renderer: CanvasRenderer,
) : GLSurfaceView(context) {

    var onTouchCallback: ((MotionEvent) -> Boolean)? = null

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return onTouchCallback?.invoke(event) ?: super.onTouchEvent(event)
    }
}

@Composable
fun GlCanvasView(
    renderer: CanvasRenderer,
    onTouch: (MotionEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            PaintGlSurfaceView(context, renderer).apply {
                onTouchCallback = onTouch
            }
        },
        modifier = modifier,
    )
}

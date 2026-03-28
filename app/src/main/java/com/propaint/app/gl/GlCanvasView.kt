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
    var onHoverCallback: ((MotionEvent) -> Boolean)? = null

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return onTouchCallback?.invoke(event) ?: super.onTouchEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        return onHoverCallback?.invoke(event) ?: super.onHoverEvent(event)
    }
}

@Composable
fun GlCanvasView(
    renderer: CanvasRenderer,
    onTouch: (MotionEvent) -> Boolean,
    onHover: ((MotionEvent) -> Boolean)? = null,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            PaintGlSurfaceView(context, renderer).apply {
                onTouchCallback = onTouch
                onHoverCallback = onHover
            }
        },
        modifier = modifier,
    )
}

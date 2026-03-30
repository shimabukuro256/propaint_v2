package com.propaint.app.flutter

import android.content.Context
import android.view.View
import com.propaint.app.gl.CanvasRenderer
import com.propaint.app.gl.PaintGlSurfaceView
import com.propaint.app.viewmodel.PaintViewModel
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * Flutter PlatformView として PaintGlSurfaceView を公開する Factory。
 *
 * タッチイベントは PlatformView 経由で直接 Android View に転送される。
 * MethodChannel を経由しないためレイテンシが最小。
 */
class PaintCanvasViewFactory(
    private val viewModel: PaintViewModel,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        return PaintCanvasPlatformView(context, viewModel)
    }
}

private class PaintCanvasPlatformView(
    context: Context,
    private val viewModel: PaintViewModel,
) : PlatformView {

    private val glView: PaintGlSurfaceView = PaintGlSurfaceView(
        context, viewModel.renderer,
    ).apply {
        // タッチ/ホバーを既存 ViewModel ハンドラに接続
        onTouchCallback = { event -> viewModel.onTouchEvent(event) }
        onHoverCallback = { event -> viewModel.onHoverEvent(event) }
    }

    override fun getView(): View = glView

    override fun dispose() {
        // GLSurfaceView のライフサイクルは Activity に委譲
    }
}

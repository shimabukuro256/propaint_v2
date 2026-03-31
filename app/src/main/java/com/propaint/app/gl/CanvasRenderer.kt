package com.propaint.app.gl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.propaint.app.engine.CanvasDocument
import com.propaint.app.engine.DirtyTileTracker
import com.propaint.app.engine.PaintDebug
import com.propaint.app.engine.Tile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 表示専用 Renderer。GPU は描画処理に一切関与しない。 */
class CanvasRenderer : GLSurfaceView.Renderer {
    var document: CanvasDocument? = null
    val pendingTransform = AtomicReference<FloatArray?>(null)
    @Volatile var zoom = 1f; @Volatile var panX = 0f
    @Volatile var panY = 0f; @Volatile var rotation = 0f
    var surfaceWidth = 0; private set
    var surfaceHeight = 0; private set

    // ── ブラシカーソル状態 (ViewModel から設定される) ──
    @Volatile var cursorX = 0f
    @Volatile var cursorY = 0f
    @Volatile var cursorRadius = 0f
    @Volatile var cursorVisible = false

    // ── 選択ドラッグ矩形 (スクリーン座標 [x1,y1,x2,y2]、null=非表示) ──
    @Volatile var selDragRect: FloatArray? = null

    // ── 選択マスクテクスチャ (マーチングアンツ表示用) ──
    /** ViewModel からアトミックに受け渡される選択マスクデータ (null=クリア) */
    val pendingSelMask = AtomicReference<ByteArray?>(null)
    /** 選択マスクが更新されたことを示すフラグ (null の場合もクリア操作として送る) */
    @Volatile var selMaskDirty = false

    private lateinit var quadProg: GlProgram
    private lateinit var cursorProg: GlProgram
    private lateinit var selOverlayProg: GlProgram
    private var canvasTexId = 0; private var canvasTexW = 0; private var canvasTexH = 0
    private var selTexId = 0; private var selTexW = 0; private var selTexH = 0
    private var hasSelMask = false
    private var animStartTime = System.nanoTime()
    private var uploadBuffer: ByteBuffer? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.16f, 0.16f, 0.16f, 1f)
        PaintDebug.d(PaintDebug.GL) { "[onSurfaceCreated] resetting textures, old texId=$canvasTexId" }
        canvasTexId = 0; canvasTexW = 0; canvasTexH = 0
        selTexId = 0; selTexW = 0; selTexH = 0; hasSelMask = false
        animStartTime = System.nanoTime()
        quadProg = GlProgram(Shaders.QUAD_VERT, Shaders.QUAD_FRAG)
        cursorProg = GlProgram(Shaders.CURSOR_VERT, Shaders.CURSOR_FRAG)
        selOverlayProg = GlProgram(Shaders.QUAD_VERT, Shaders.SEL_OVERLAY_FRAG)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        surfaceWidth = w; surfaceHeight = h; GLES20.glViewport(0, 0, w, h)
        if (canvasTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(canvasTexId), 0); canvasTexId = 0 }
        if (selTexId != 0) { GLES20.glDeleteTextures(1, intArrayOf(selTexId), 0); selTexId = 0; hasSelMask = false }
    }

    override fun onDrawFrame(gl: GL10?) {
        val doc = document ?: run { GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); return }
        pendingTransform.getAndSet(null)?.let { zoom = it[0]; panX = it[1]; panY = it[2]; rotation = it[3] }

        val dw = doc.width; val dh = doc.height
        val txc = (dw + Tile.SIZE - 1) / Tile.SIZE; val tyc = (dh + Tile.SIZE - 1) / Tile.SIZE
        if (canvasTexId == 0 || canvasTexW != dw || canvasTexH != dh) {
            canvasTexId = createTexture(dw, dh); canvasTexW = dw; canvasTexH = dh
            doc.dirtyTracker.markFullRebuild()
        }

        val full = doc.dirtyTracker.checkAndClearFullRebuild()
        if (full) { doc.rebuildCompositeCache(); uploadAllTiles(doc, txc, tyc, dw, dh) }
        else {
            val dirty = doc.dirtyTracker.drain()
            if (dirty.isNotEmpty()) {
                for (c in dirty) {
                    val tx = DirtyTileTracker.unpackX(c); val ty = DirtyTileTracker.unpackY(c)
                    if (tx < txc && ty < tyc) { val idx = ty * txc + tx; doc.rebuildCompositeTile(tx, ty, idx); uploadTile(doc, tx, ty, idx, dw, dh) }
                }
            }
        }

        // 選択マスクテクスチャの更新
        if (selMaskDirty) {
            selMaskDirty = false
            val maskData = pendingSelMask.getAndSet(null)
            if (maskData != null) {
                uploadSelectionMask(maskData, dw, dh)
                hasSelMask = true
            } else {
                hasSelMask = false
            }
        }

        GLES20.glClearColor(0.16f, 0.16f, 0.16f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawCanvasToScreen(dw, dh)

        // 選択マスクオーバーレイ (マーチングアンツ)
        if (hasSelMask) {
            drawSelectionOverlay(dw, dh)
        }

        if (cursorVisible && cursorRadius > 0.5f) {
            drawCursorCircle()
        }
        selDragRect?.let { drawSelectionDragRect(it) }
    }

    private fun createTexture(w: Int, h: Int): Int {
        val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); val t = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until w * h) { buf.put(-1); buf.put(-1); buf.put(-1); buf.put(-1) }
        buf.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0); return t
    }

    // ── 選択マスクテクスチャ ───────────────────────────────────────

    private fun uploadSelectionMask(mask: ByteArray, w: Int, h: Int) {
        if (selTexId == 0 || selTexW != w || selTexH != h) {
            if (selTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(selTexId), 0)
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); selTexId = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, selTexId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            selTexW = w; selTexH = h
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, selTexId)
        }
        val expectedSize = w * h
        if (mask.size < expectedSize) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            return
        }
        val buf = ByteBuffer.allocateDirect(expectedSize).order(ByteOrder.nativeOrder())
        buf.put(mask, 0, expectedSize)
        buf.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA, w, h, 0,
            GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, buf)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun drawSelectionOverlay(dw: Int, dh: Int) {
        if (selTexId == 0) return
        val sw = surfaceWidth.toFloat(); val sh = surfaceHeight.toFloat()
        if (sw <= 0f || sh <= 0f) return

        // キャンバスと同じ変換でオーバーレイを描画
        val bs = min(sw / dw, sh / dh); val fs = bs * zoom
        val cx = sw / 2f + panX; val cy = sh / 2f + panY
        val hw = dw / 2f; val hh = dh / 2f
        val c = cos(rotation.toDouble()).toFloat(); val s = sin(rotation.toDouble()).toFloat()
        fun tx(ox: Float, oy: Float) = cx + fs * (ox * c - oy * s) to cy + fs * (ox * s + oy * c)
        val (tlx, tly) = tx(-hw, -hh); val (trx, try_) = tx(hw, -hh)
        val (brx, bry) = tx(hw, hh); val (blx, bly) = tx(-hw, hh)
        val v = floatArrayOf(
            tlx, tly, 0f, 0f, trx, try_, 1f, 0f, brx, bry, 1f, 1f,
            tlx, tly, 0f, 0f, brx, bry, 1f, 1f, blx, bly, 0f, 1f
        )
        val mvp = FloatArray(16); ortho(mvp, 0f, sw, sh, 0f)
        val time = ((System.nanoTime() - animStartTime) / 1e9).toFloat()

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        selOverlayProg.use()
        GLES20.glUniformMatrix4fv(selOverlayProg.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(selOverlayProg.uniform("uTime"), time)
        GLES20.glUniform2f(selOverlayProg.uniform("uTexelSize"), 1f / dw, 1f / dh)
        GLES20.glUniform1i(selOverlayProg.uniform("uSelMask"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, selTexId)

        val buf = v.toFloatBuffer()
        val p = selOverlayProg.attrib("aPos"); val u = selOverlayProg.attrib("aUV")
        GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(u)
        buf.position(0); GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 16, buf)
        buf.position(2); GLES20.glVertexAttribPointer(u, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(u)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun uploadAllTiles(doc: CanvasDocument, txc: Int, tyc: Int, dw: Int, dh: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTexId)
        for (ty in 0 until tyc) for (tx in 0 until txc) uploadTileInner(doc, tx, ty, ty * txc + tx, dw, dh)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun uploadTile(doc: CanvasDocument, tx: Int, ty: Int, idx: Int, dw: Int, dh: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTexId)
        uploadTileInner(doc, tx, ty, idx, dw, dh)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private var uploadIntBuf: IntArray? = null

    private fun uploadTileInner(doc: CanvasDocument, tx: Int, ty: Int, idx: Int, dw: Int, dh: Int) {
        val data = doc.compositeCache[idx] ?: return
        val bx = tx * Tile.SIZE; val by = ty * Tile.SIZE
        val tw = min(Tile.SIZE, dw - bx); val th = min(Tile.SIZE, dh - by)
        if (tw <= 0 || th <= 0) return
        val pixelCount = tw * th
        val sz = pixelCount * 4
        val buf = uploadBuffer?.let { if (it.capacity() >= sz) it else null }
            ?: ByteBuffer.allocateDirect(sz).order(ByteOrder.nativeOrder()).also { uploadBuffer = it }
        val ib = uploadIntBuf?.let { if (it.size >= pixelCount) it else null }
            ?: IntArray(pixelCount).also { uploadIntBuf = it }
        var dst = 0
        for (ly in 0 until th) { val off = ly * Tile.SIZE
            for (lx in 0 until tw) { val p = data[off + lx]
                ib[dst++] = (p and 0xFF00FF00.toInt()) or
                    ((p and 0xFF) shl 16) or
                    ((p ushr 16) and 0xFF)
            }
        }
        buf.position(0); buf.limit(sz)
        buf.asIntBuffer().put(ib, 0, pixelCount)
        buf.position(0)
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, bx, by, tw, th, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
    }

    private fun drawCanvasToScreen(dw: Int, dh: Int) {
        val sw = surfaceWidth.toFloat(); val sh = surfaceHeight.toFloat()
        val bs = min(sw / dw, sh / dh); val fs = bs * zoom
        val cx = sw / 2f + panX; val cy = sh / 2f + panY
        val hw = dw / 2f; val hh = dh / 2f
        val c = cos(rotation.toDouble()).toFloat(); val s = sin(rotation.toDouble()).toFloat()
        fun tx(ox: Float, oy: Float) = cx + fs * (ox * c - oy * s) to cy + fs * (ox * s + oy * c)
        val (tlx, tly) = tx(-hw, -hh); val (trx, try_) = tx(hw, -hh)
        val (brx, bry) = tx(hw, hh); val (blx, bly) = tx(-hw, hh)
        val v = floatArrayOf(tlx, tly, 0f, 0f, trx, try_, 1f, 0f, brx, bry, 1f, 1f,
            tlx, tly, 0f, 0f, brx, bry, 1f, 1f, blx, bly, 0f, 1f)
        val mvp = FloatArray(16); ortho(mvp, 0f, sw, sh, 0f)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        quadProg.use()
        GLES20.glUniformMatrix4fv(quadProg.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(quadProg.uniform("uAlpha"), 1f)
        GLES20.glUniform1i(quadProg.uniform("uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTexId)
        val buf = v.toFloatBuffer(); val p = quadProg.attrib("aPos"); val u = quadProg.attrib("aUV")
        GLES20.glEnableVertexAttribArray(p); GLES20.glEnableVertexAttribArray(u)
        buf.position(0); GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 16, buf)
        buf.position(2); GLES20.glVertexAttribPointer(u, 2, GLES20.GL_FLOAT, false, 16, buf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(p); GLES20.glDisableVertexAttribArray(u)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    // ── 選択ドラッグ矩形の描画 ───────────────────────────────────────

    private fun drawSelectionDragRect(rect: FloatArray) {
        if (rect.size < 4) return
        val x1 = rect[0]; val y1 = rect[1]; val x2 = rect[2]; val y2 = rect[3]
        val sw = surfaceWidth.toFloat(); val sh = surfaceHeight.toFloat()
        if (sw <= 0f || sh <= 0f) return

        val verts = floatArrayOf(x1, y1, x2, y1, x2, y2, x1, y2)
        val mvp = FloatArray(16); ortho(mvp, 0f, sw, sh, 0f)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        cursorProg.use()
        GLES20.glUniformMatrix4fv(cursorProg.uniform("uMVP"), 1, false, mvp, 0)
        val aPos = cursorProg.attrib("aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        val buf = verts.toFloatBuffer()
        buf.position(0); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glLineWidth(2.5f)
        GLES20.glUniform4f(cursorProg.uniform("uColor"), 0f, 0f, 0f, 0.7f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
        GLES20.glLineWidth(1.2f)
        GLES20.glUniform4f(cursorProg.uniform("uColor"), 1f, 1f, 1f, 0.9f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    // ── ブラシカーソル円の描画 ─────────────────────────────────────

    private fun drawCursorCircle() {
        val cx = cursorX; val cy = cursorY; val r = cursorRadius
        val sw = surfaceWidth.toFloat(); val sh = surfaceHeight.toFloat()
        if (sw <= 0f || sh <= 0f) return
        val verts = FloatArray(CIRCLE_SEGMENTS * 2)
        val step = (2.0 * Math.PI / CIRCLE_SEGMENTS).toFloat()
        for (i in 0 until CIRCLE_SEGMENTS) {
            val angle = i * step
            verts[i * 2] = cx + r * cos(angle)
            verts[i * 2 + 1] = cy + r * sin(angle)
        }
        val mvp = FloatArray(16); ortho(mvp, 0f, sw, sh, 0f)
        val buf = verts.toFloatBuffer()
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glLineWidth(2.5f)
        cursorProg.use()
        GLES20.glUniformMatrix4fv(cursorProg.uniform("uMVP"), 1, false, mvp, 0)
        val aPos = cursorProg.attrib("aPos")
        GLES20.glEnableVertexAttribArray(aPos)
        buf.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glUniform4f(cursorProg.uniform("uColor"), 0f, 0f, 0f, 0.7f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, CIRCLE_SEGMENTS)
        GLES20.glLineWidth(1.2f)
        GLES20.glUniform4f(cursorProg.uniform("uColor"), 1f, 1f, 1f, 0.9f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, CIRCLE_SEGMENTS)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    fun cleanup() {
        if (canvasTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(canvasTexId), 0)
        if (selTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(selTexId), 0)
        if (::quadProg.isInitialized) quadProg.delete()
        if (::cursorProg.isInitialized) cursorProg.delete()
        if (::selOverlayProg.isInitialized) selOverlayProg.delete()
    }

    companion object {
        private const val CIRCLE_SEGMENTS = 64

        fun ortho(m: FloatArray, l: Float, r: Float, b: Float, t: Float) {
            val rw = 2f / (r - l); val rh = 2f / (t - b)
            m[0] = rw; m[4] = 0f; m[8] = 0f; m[12] = -(r + l) / (r - l)
            m[1] = 0f; m[5] = rh; m[9] = 0f; m[13] = -(t + b) / (t - b)
            m[2] = 0f; m[6] = 0f; m[10] = -1f; m[14] = 0f
            m[3] = 0f; m[7] = 0f; m[11] = 0f; m[15] = 1f
        }
    }
}

fun FloatArray.toFloatBuffer(): FloatBuffer {
    val bb = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
    val fb = bb.asFloatBuffer(); fb.put(this); fb.position(0); return fb
}

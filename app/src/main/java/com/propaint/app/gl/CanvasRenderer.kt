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

    @Volatile var zoom = 1f
    @Volatile var panX = 0f
    @Volatile var panY = 0f
    @Volatile var rotation = 0f

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

    private var canvasTexId = 0
    private var canvasTexW = 0
    private var canvasTexH = 0

    private var selTexId = 0
    private var selTexW = 0
    private var selTexH = 0
    private var hasSelMask = false

    private var animStartTime = System.nanoTime()
    private var uploadBuffer: ByteBuffer? = null
    private var uploadIntBuf: IntArray? = null

    // ── フレーム毎の再利用バッファ (GC 回避) ──
    private val reusableMvp = FloatArray(16)
    private val reusableQuadVerts = FloatArray(24) // 6頂点 × 4 (x,y,u,v)
    private val reusableCircleVerts = FloatArray(CIRCLE_SEGMENTS * 2)
    private val reusableRectVerts = FloatArray(8) // 4頂点 × 2 (x,y)
    private var reusableVertBuf: FloatBuffer? = null
    private var reusableVertBufCapacity = 0
    private var selMaskUploadBuf: ByteBuffer? = null

    // ── ライフサイクル ────────────────────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.16f, 0.16f, 0.16f, 1f)
        PaintDebug.d(PaintDebug.GL) { "[onSurfaceCreated] resetting textures, old texId=$canvasTexId" }

        canvasTexId = 0
        canvasTexW = 0
        canvasTexH = 0
        selTexId = 0
        selTexW = 0
        selTexH = 0
        hasSelMask = false
        animStartTime = System.nanoTime()

        quadProg = GlProgram(Shaders.QUAD_VERT, Shaders.QUAD_FRAG)
        cursorProg = GlProgram(Shaders.CURSOR_VERT, Shaders.CURSOR_FRAG)
        selOverlayProg = GlProgram(Shaders.QUAD_VERT, Shaders.SEL_OVERLAY_FRAG)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        surfaceWidth = w
        surfaceHeight = h
        GLES20.glViewport(0, 0, w, h)

        if (canvasTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(canvasTexId), 0)
            canvasTexId = 0
        }
        if (selTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(selTexId), 0)
            selTexId = 0
            hasSelMask = false
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val doc = document ?: run {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }

        pendingTransform.getAndSet(null)?.let {
            zoom = it[0]; panX = it[1]; panY = it[2]; rotation = it[3]
        }

        val dw = doc.width
        val dh = doc.height
        val tileCountX = (dw + Tile.SIZE - 1) / Tile.SIZE
        val tileCountY = (dh + Tile.SIZE - 1) / Tile.SIZE

        // キャンバステクスチャの確保・再構築
        if (canvasTexId == 0 || canvasTexW != dw || canvasTexH != dh) {
            canvasTexId = createCanvasTexture(dw, dh)
            canvasTexW = dw
            canvasTexH = dh
            doc.dirtyTracker.markFullRebuild()
        }

        // ダーティタイルの合成・アップロード
        updateDirtyTiles(doc, tileCountX, tileCountY, dw, dh)

        // 選択マスクテクスチャの更新
        updateSelectionMask(dw, dh)

        // 描画
        GLES20.glClearColor(0.16f, 0.16f, 0.16f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        drawCanvasToScreen(dw, dh)

        if (hasSelMask) {
            drawSelectionOverlay(dw, dh)
        }
        if (cursorVisible && cursorRadius > 0.5f) {
            drawCursorCircle()
        }
        selDragRect?.let { drawSelectionDragRect(it) }
    }

    // ── テクスチャ生成 ───────────────────────────────────────────────

    /** キャンバス表示用テクスチャを生成し、白色 (0xFFFFFFFF) で初期化する */
    private fun createCanvasTexture(w: Int, h: Int): Int {
        val texId = createTexture(GLES20.GL_LINEAR)

        // 白色で初期化
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until w * h) {
            buf.put(-1) // R=0xFF
            buf.put(-1) // G=0xFF
            buf.put(-1) // B=0xFF
            buf.put(-1) // A=0xFF
        }
        buf.position(0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        return texId
    }

    /**
     * テクスチャを生成し、フィルタ・ラップパラメータを設定する。
     * glTexImage2D は呼び出し元で行う。
     */
    private fun createTexture(filter: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        val texId = ids[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        return texId
    }

    // ── タイル更新 ──────────────────────────────────────────────────

    /**
     * ダーティタイルの合成キャッシュを更新し、テクスチャにアップロードする。
     * 合成結果は backBuffer に書かれ、publishCompositeTile で frontBuffer にコピーされる。
     * uploadTileInner は frontBuffer (compositeCache) を読むため、publish 後にアップロードする。
     */
    private fun updateDirtyTiles(doc: CanvasDocument, tileCountX: Int, tileCountY: Int, dw: Int, dh: Int) {
        val fullRebuild = doc.dirtyTracker.checkAndClearFullRebuild()
        if (fullRebuild) {
            doc.rebuildCompositeCache()
            doc.publishAllCompositeTiles()
            uploadAllTiles(doc, tileCountX, tileCountY, dw, dh)
        } else {
            val dirty = doc.dirtyTracker.drain()
            if (dirty.isNotEmpty()) {
                for (coord in dirty) {
                    val tx = DirtyTileTracker.unpackX(coord)
                    val ty = DirtyTileTracker.unpackY(coord)
                    if (tx < tileCountX && ty < tileCountY) {
                        val idx = ty * tileCountX + tx
                        doc.rebuildCompositeTile(tx, ty, idx)
                        doc.publishCompositeTile(idx)
                        uploadTile(doc, tx, ty, idx, dw, dh)
                    }
                }
            }
        }
    }

    private fun uploadAllTiles(doc: CanvasDocument, tileCountX: Int, tileCountY: Int, dw: Int, dh: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTexId)
        for (ty in 0 until tileCountY) {
            for (tx in 0 until tileCountX) {
                uploadTileInner(doc, tx, ty, ty * tileCountX + tx, dw, dh)
            }
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun uploadTile(doc: CanvasDocument, tx: Int, ty: Int, idx: Int, dw: Int, dh: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTexId)
        uploadTileInner(doc, tx, ty, idx, dw, dh)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * 1タイル分のピクセルデータをテクスチャにアップロードする。
     * 呼び出し前に glBindTexture(canvasTexId) 済みであること。
     */
    private fun uploadTileInner(doc: CanvasDocument, tx: Int, ty: Int, idx: Int, dw: Int, dh: Int) {
        val data = doc.compositeCache[idx] ?: return

        val baseX = tx * Tile.SIZE
        val baseY = ty * Tile.SIZE
        val tileW = min(Tile.SIZE, dw - baseX)
        val tileH = min(Tile.SIZE, dh - baseY)
        if (tileW <= 0 || tileH <= 0) return

        val pixelCount = tileW * tileH
        val byteSize = pixelCount * 4

        // アップロードバッファの確保 (再利用)
        val buf = uploadBuffer?.let { if (it.capacity() >= byteSize) it else null }
            ?: ByteBuffer.allocateDirect(byteSize).order(ByteOrder.nativeOrder()).also { uploadBuffer = it }
        val intBuf = uploadIntBuf?.let { if (it.size >= pixelCount) it else null }
            ?: IntArray(pixelCount).also { uploadIntBuf = it }

        // ARGB (Tile内部形式) → RGBA (OpenGL形式) へのスウィズル
        // G,A チャネルは同じ位置のため保持し、R と B を入れ替える
        var dst = 0
        for (ly in 0 until tileH) {
            val rowOffset = ly * Tile.SIZE
            for (lx in 0 until tileW) {
                val argb = data[rowOffset + lx]
                intBuf[dst++] = (argb and 0xFF00FF00.toInt()) or  // G, A はそのまま
                    ((argb and 0xFF) shl 16) or                    // B → R 位置へ
                    ((argb ushr 16) and 0xFF)                      // R → B 位置へ
            }
        }

        buf.position(0)
        buf.limit(byteSize)
        buf.asIntBuffer().put(intBuf, 0, pixelCount)
        buf.position(0)

        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D, 0,
            baseX, baseY, tileW, tileH,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
    }

    // ── 選択マスク ──────────────────────────────────────────────────

    /** 選択マスクの更新チェック・テクスチャアップロード */
    private fun updateSelectionMask(dw: Int, dh: Int) {
        if (!selMaskDirty) return
        selMaskDirty = false

        val maskData = pendingSelMask.getAndSet(null)
        if (maskData != null) {
            uploadSelectionMask(maskData, dw, dh)
            hasSelMask = true
        } else {
            hasSelMask = false
        }
    }

    private fun uploadSelectionMask(mask: ByteArray, w: Int, h: Int) {
        // テクスチャの再生成 (サイズ変更時または初回)
        if (selTexId == 0 || selTexW != w || selTexH != h) {
            if (selTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(selTexId), 0)
            }
            selTexId = createTexture(GLES20.GL_NEAREST)
            selTexW = w
            selTexH = h
        }

        val expectedSize = w * h
        if (mask.size < expectedSize) return

        val buf = selMaskUploadBuf?.let { if (it.capacity() >= expectedSize) it else null }
            ?: ByteBuffer.allocateDirect(expectedSize).order(ByteOrder.nativeOrder()).also { selMaskUploadBuf = it }
        buf.clear()
        buf.put(mask, 0, expectedSize)
        buf.position(0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, selTexId)
        // GL_ALPHA は 1 バイト/ピクセル。幅が 4 の倍数でないと既定値 4 で行がずれて斜めに歪む。
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_ALPHA,
            w, h, 0, GLES20.GL_ALPHA, GLES20.GL_UNSIGNED_BYTE, buf
        )
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    // ── キャンバス変換 ──────────────────────────────────────────────

    /**
     * キャンバスの4頂点をスクリーン座標に変換し、再利用配列 reusableQuadVerts に書き込んで返す。
     * 頂点フォーマット: [x, y, u, v] × 6  (stride = 16 bytes)
     */
    private fun buildCanvasQuadVertices(docWidth: Int, docHeight: Int): FloatArray {
        val sw = surfaceWidth.toFloat()
        val sh = surfaceHeight.toFloat()
        val baseScale = min(sw / docWidth, sh / docHeight)
        val finalScale = baseScale * zoom

        val centerX = sw / 2f + panX
        val centerY = sh / 2f + panY
        val halfW = docWidth / 2f
        val halfH = docHeight / 2f

        val cosR = cos(rotation.toDouble()).toFloat()
        val sinR = sin(rotation.toDouble()).toFloat()

        // ローカル座標 → スクリーン座標への変換
        fun transform(ox: Float, oy: Float): Pair<Float, Float> =
            (centerX + finalScale * (ox * cosR - oy * sinR)) to
            (centerY + finalScale * (ox * sinR + oy * cosR))

        val (tlx, tly) = transform(-halfW, -halfH) // 左上
        val (trx, trY) = transform(halfW, -halfH)  // 右上
        val (brx, bry) = transform(halfW, halfH)    // 右下
        val (blx, bly) = transform(-halfW, halfH)   // 左下

        // 2三角形 (TL→TR→BR, TL→BR→BL) で構成する quad → 再利用配列に書き込み
        val v = reusableQuadVerts
        v[0]  = tlx; v[1]  = tly; v[2]  = 0f; v[3]  = 0f
        v[4]  = trx; v[5]  = trY; v[6]  = 1f; v[7]  = 0f
        v[8]  = brx; v[9]  = bry; v[10] = 1f; v[11] = 1f
        v[12] = tlx; v[13] = tly; v[14] = 0f; v[15] = 0f
        v[16] = brx; v[17] = bry; v[18] = 1f; v[19] = 1f
        v[20] = blx; v[21] = bly; v[22] = 0f; v[23] = 1f
        return v
    }

    // ── キャンバス描画 ──────────────────────────────────────────────

    private fun drawCanvasToScreen(dw: Int, dh: Int) {
        val sw = surfaceWidth.toFloat()
        val sh = surfaceHeight.toFloat()

        val vertices = buildCanvasQuadVertices(dw, dh)
        val mvp = reusableMvp
        ortho(mvp, 0f, sw, sh, 0f)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        quadProg.use()
        GLES20.glUniformMatrix4fv(quadProg.uniform("uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(quadProg.uniform("uAlpha"), 1f)
        GLES20.glUniform1i(quadProg.uniform("uTex"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, canvasTexId)

        drawTexturedQuad(quadProg, vertices)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    // ── 選択オーバーレイ描画 ────────────────────────────────────────

    private fun drawSelectionOverlay(dw: Int, dh: Int) {
        if (selTexId == 0) return
        val sw = surfaceWidth.toFloat()
        val sh = surfaceHeight.toFloat()
        if (sw <= 0f || sh <= 0f) return

        val vertices = buildCanvasQuadVertices(dw, dh)
        val mvp = reusableMvp
        ortho(mvp, 0f, sw, sh, 0f)
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

        drawTexturedQuad(selOverlayProg, vertices)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /**
     * UV 付き頂点配列で quad を描画する共通処理。
     * 頂点フォーマット: [x, y, u, v] × 6  (stride = 16 bytes)
     */
    private fun drawTexturedQuad(prog: GlProgram, vertices: FloatArray) {
        val buf = getVertexBuffer(vertices)
        val posAttrib = prog.attrib("aPos")
        val uvAttrib = prog.attrib("aUV")

        GLES20.glEnableVertexAttribArray(posAttrib)
        GLES20.glEnableVertexAttribArray(uvAttrib)

        buf.position(0)
        GLES20.glVertexAttribPointer(posAttrib, 2, GLES20.GL_FLOAT, false, 16, buf)
        buf.position(2)
        GLES20.glVertexAttribPointer(uvAttrib, 2, GLES20.GL_FLOAT, false, 16, buf)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

        GLES20.glDisableVertexAttribArray(posAttrib)
        GLES20.glDisableVertexAttribArray(uvAttrib)
    }

    // ── 選択ドラッグ矩形の描画 ───────────────────────────────────────

    private fun drawSelectionDragRect(rect: FloatArray) {
        if (rect.size < 4) return
        val sw = surfaceWidth.toFloat()
        val sh = surfaceHeight.toFloat()
        if (sw <= 0f || sh <= 0f) return

        val x1 = rect[0]; val y1 = rect[1]
        val x2 = rect[2]; val y2 = rect[3]
        val verts = reusableRectVerts
        verts[0] = x1; verts[1] = y1; verts[2] = x2; verts[3] = y1
        verts[4] = x2; verts[5] = y2; verts[6] = x1; verts[7] = y2

        drawDoubleOutlineLineLoop(verts, 4)
    }

    // ── ブラシカーソル円の描画 ─────────────────────────────────────

    private fun drawCursorCircle() {
        val cx = cursorX
        val cy = cursorY
        val r = cursorRadius
        val sw = surfaceWidth.toFloat()
        val sh = surfaceHeight.toFloat()
        if (sw <= 0f || sh <= 0f) return

        val verts = reusableCircleVerts
        val step = (2.0 * Math.PI / CIRCLE_SEGMENTS).toFloat()
        for (i in 0 until CIRCLE_SEGMENTS) {
            val angle = i * step
            verts[i * 2] = cx + r * cos(angle)
            verts[i * 2 + 1] = cy + r * sin(angle)
        }

        drawDoubleOutlineLineLoop(verts, CIRCLE_SEGMENTS)
    }

    /**
     * 黒太線 + 白細線のダブルアウトラインで LINE_LOOP を描画する共通処理。
     * カーソル円・選択矩形の両方で使用。
     */
    private fun drawDoubleOutlineLineLoop(verts: FloatArray, vertexCount: Int) {
        val sw = surfaceWidth.toFloat()
        val sh = surfaceHeight.toFloat()
        val mvp = reusableMvp
        ortho(mvp, 0f, sw, sh, 0f)

        val buf = getVertexBuffer(verts, vertexCount * 2)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        cursorProg.use()
        GLES20.glUniformMatrix4fv(cursorProg.uniform("uMVP"), 1, false, mvp, 0)

        val posAttrib = cursorProg.attrib("aPos")
        GLES20.glEnableVertexAttribArray(posAttrib)
        buf.position(0)
        GLES20.glVertexAttribPointer(posAttrib, 2, GLES20.GL_FLOAT, false, 0, buf)

        // 1パス目: 黒太線 (影)
        GLES20.glLineWidth(2.5f)
        GLES20.glUniform4f(cursorProg.uniform("uColor"), 0f, 0f, 0f, 0.7f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, vertexCount)

        // 2パス目: 白細線 (ハイライト)
        GLES20.glLineWidth(1.2f)
        GLES20.glUniform4f(cursorProg.uniform("uColor"), 1f, 1f, 1f, 0.9f)
        GLES20.glDrawArrays(GLES20.GL_LINE_LOOP, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(posAttrib)
    }

    // ── リソース解放 ────────────────────────────────────────────────

    fun cleanup() {
        if (canvasTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(canvasTexId), 0)
        if (selTexId != 0) GLES20.glDeleteTextures(1, intArrayOf(selTexId), 0)
        if (::quadProg.isInitialized) quadProg.delete()
        if (::cursorProg.isInitialized) cursorProg.delete()
        if (::selOverlayProg.isInitialized) selOverlayProg.delete()
    }

    /** 再利用可能な FloatBuffer にデータを書き込んで返す (フレーム毎の allocateDirect を回避) */
    private fun getVertexBuffer(data: FloatArray, count: Int = data.size): FloatBuffer {
        if (reusableVertBufCapacity < count) {
            reusableVertBuf = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            reusableVertBufCapacity = count
        }
        val fb = reusableVertBuf!!
        fb.clear()
        fb.put(data, 0, count)
        fb.position(0)
        return fb
    }

    companion object {
        private const val CIRCLE_SEGMENTS = 64

        fun ortho(m: FloatArray, l: Float, r: Float, b: Float, t: Float) {
            val rw = 2f / (r - l)
            val rh = 2f / (t - b)
            m[0]  = rw;  m[4]  = 0f;  m[8]  = 0f;   m[12] = -(r + l) / (r - l)
            m[1]  = 0f;  m[5]  = rh;  m[9]  = 0f;   m[13] = -(t + b) / (t - b)
            m[2]  = 0f;  m[6]  = 0f;  m[10] = -1f;  m[14] = 0f
            m[3]  = 0f;  m[7]  = 0f;  m[11] = 0f;   m[15] = 1f
        }
    }
}

fun FloatArray.toFloatBuffer(): FloatBuffer {
    val bb = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
    val fb = bb.asFloatBuffer()
    fb.put(this)
    fb.position(0)
    return fb
}

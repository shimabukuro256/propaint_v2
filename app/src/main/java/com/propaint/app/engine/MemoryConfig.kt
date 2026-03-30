package com.propaint.app.engine

import android.app.ActivityManager
import android.content.Context

/**
 * デバイスの物理 RAM に基づいてアプリ全体のメモリ使用量を動的に調整する設定シングルトン。
 *
 * 各コンポーネントのメモリ上限を一元管理し、デバイスの能力に応じた最適な設定を提供。
 *
 * 設定可能なメモリティア:
 *   2GB / 4GB / 6GB / 8GB / 10GB / 12GB / 14GB / 16GB
 */
object MemoryConfig {

    // ── 公開パラメータ (各コンポーネントが参照) ──────────────────────

    /** DabMask の最大直径 (px)。大ブラシでのダウンスケール閾値。 */
    var maxDabDiameter: Int = 512; private set

    /** BrushEngine ぼかし処理の最大半径 (px)。水彩/筆のぼかし領域制限。 */
    var maxBlurRadius: Int = 512; private set

    /** Undo/Redo スタックの最大エントリ数 */
    var maxUndoEntries: Int = 50; private set

    /** 作成可能な最大キャンバスサイズ (px)。幅・高さの上限。 */
    var maxCanvasSize: Int = 4096; private set

    /** 最大レイヤー数 */
    var maxLayers: Int = 16; private set

    /** 自動保存間隔 (ms)。メモリが少ないデバイスでは頻繁に保存。 */
    var autoSaveIntervalMs: Long = 60_000L; private set

    /** デバイスの総 RAM (MB) */
    var deviceRamMb: Int = 0; private set

    /** 現在のメモリティア名 */
    var tierName: String = "unknown"; private set

    /** アプリに割り当て可能なヒープメモリ上限 (MB) */
    var appHeapLimitMb: Int = 256; private set

    // ── 初期化 ──────────────────────────────────────────────────────

    private var initialized = false

    /**
     * Application/Activity の onCreate で呼び出す。
     * デバイスの物理 RAM を取得し、適切なメモリティアを設定する。
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        deviceRamMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        appHeapLimitMb = am.memoryClass // 標準ヒープ上限
        val largeHeap = am.largeMemoryClass // largeHeap 上限

        // largeHeap が使える場合はそちらを参照
        if (largeHeap > appHeapLimitMb) {
            appHeapLimitMb = largeHeap
        }

        applyTier(deviceRamMb)

        PaintDebug.d(PaintDebug.Perf) {
            "[MemoryConfig] RAM=${deviceRamMb}MB heap=${appHeapLimitMb}MB tier=$tierName " +
            "dabMax=$maxDabDiameter blurMax=$maxBlurRadius undo=$maxUndoEntries " +
            "canvas=$maxCanvasSize layers=$maxLayers"
        }
    }

    /**
     * デバイス RAM (MB) に基づいてメモリティアを適用。
     * テストからも呼べるように public。
     */
    fun applyTier(ramMb: Int) {
        deviceRamMb = ramMb
        when {
            ramMb < 3000 -> apply2GB()
            ramMb < 5000 -> apply4GB()
            ramMb < 7000 -> apply6GB()
            ramMb < 9000 -> apply8GB()
            ramMb < 11000 -> apply10GB()
            ramMb < 13000 -> apply12GB()
            ramMb < 15000 -> apply14GB()
            else -> apply16GB()
        }
    }

    // ── メモリティア定義 ────────────────────────────────────────────

    private fun apply2GB() {
        tierName = "2GB"
        maxDabDiameter = 256
        maxBlurRadius = 128
        maxUndoEntries = 15
        maxCanvasSize = 2048
        maxLayers = 8
        autoSaveIntervalMs = 30_000L // 30秒 (メモリ圧迫で強制終了されやすい)
    }

    private fun apply4GB() {
        tierName = "4GB"
        maxDabDiameter = 384
        maxBlurRadius = 256
        maxUndoEntries = 30
        maxCanvasSize = 3072
        maxLayers = 12
        autoSaveIntervalMs = 45_000L
    }

    private fun apply6GB() {
        tierName = "6GB"
        maxDabDiameter = 512
        maxBlurRadius = 384
        maxUndoEntries = 40
        maxCanvasSize = 4096
        maxLayers = 16
        autoSaveIntervalMs = 60_000L
    }

    private fun apply8GB() {
        tierName = "8GB"
        maxDabDiameter = 512
        maxBlurRadius = 512
        maxUndoEntries = 50
        maxCanvasSize = 6144
        maxLayers = 24
        autoSaveIntervalMs = 60_000L
    }

    private fun apply10GB() {
        tierName = "10GB"
        maxDabDiameter = 640
        maxBlurRadius = 640
        maxUndoEntries = 60
        maxCanvasSize = 8192
        maxLayers = 32
        autoSaveIntervalMs = 90_000L
    }

    private fun apply12GB() {
        tierName = "12GB"
        maxDabDiameter = 768
        maxBlurRadius = 768
        maxUndoEntries = 80
        maxCanvasSize = 10240
        maxLayers = 40
        autoSaveIntervalMs = 90_000L
    }

    private fun apply14GB() {
        tierName = "14GB"
        maxDabDiameter = 896
        maxBlurRadius = 896
        maxUndoEntries = 100
        maxCanvasSize = 12288
        maxLayers = 48
        autoSaveIntervalMs = 120_000L
    }

    private fun apply16GB() {
        tierName = "16GB"
        maxDabDiameter = 1024
        maxBlurRadius = 1024
        maxUndoEntries = 120
        maxCanvasSize = 16384
        maxLayers = 64
        autoSaveIntervalMs = 120_000L
    }

    // ── メモリ予算概算 (情報表示用) ──────────────────────────────────

    /**
     * 指定キャンバスサイズ・レイヤー数での推定メモリ使用量 (MB)。
     * ギャラリーの新規キャンバスダイアログでユーザーに表示する用途。
     */
    fun estimateMemoryMb(canvasWidth: Int, canvasHeight: Int, layerCount: Int): Int {
        val tilesX = (canvasWidth + 63) / 64
        val tilesY = (canvasHeight + 63) / 64
        val tileCount = tilesX * tilesY
        val bytesPerLayer = tileCount.toLong() * 64 * 64 * 4 // 全タイル確保時
        val layersMb = (bytesPerLayer * layerCount / (1024 * 1024)).toInt()
        val compositeMb = (bytesPerLayer / (1024 * 1024)).toInt()
        val undoMb = 1 // CoW なので小さい
        val brushBufferMb = (maxBlurRadius * 2 + 1).let { it * it * 12L / (1024 * 1024) }.toInt()
        val overheadMb = 30 // GL, Flutter, その他
        return layersMb + compositeMb + undoMb + brushBufferMb + overheadMb
    }
}

package com.propaint.app.engine

/**
 * Command Pattern for CanvasDocument operations.
 *
 * MultiLayerCommand: 複数選択レイヤーに一括適用可能。
 * ActiveLayerCommand: 複数選択中でもアクティブレイヤーのみに適用（排他制御）。
 */
sealed class PaintCommand {

    /** 複数選択レイヤーに一括適用可能なコマンド */
    sealed class MultiLayerCommand : PaintCommand()

    /** アクティブレイヤーのみに適用されるコマンド（フィルター・描画系） */
    sealed class ActiveLayerCommand : PaintCommand()

    // ── Transform（複数レイヤー対応） ─────────────────────────

    data object FlipH : MultiLayerCommand()
    data object FlipV : MultiLayerCommand()
    data object Rotate90CW : MultiLayerCommand()

    data class FreeTransform(
        val centerX: Float, val centerY: Float,
        val scaleX: Float, val scaleY: Float,
        val angleDeg: Float,
        val translateX: Float, val translateY: Float,
    ) : MultiLayerCommand()

    data class Distort(
        val srcPoints: FloatArray, val dstPoints: FloatArray,
    ) : MultiLayerCommand()

    data class MeshWarp(
        val gridCols: Int, val gridRows: Int, val controlPoints: FloatArray,
    ) : MultiLayerCommand()

    // ── 選択範囲操作（複数レイヤー対応） ──────────────────────

    data object DeleteSelection : MultiLayerCommand()
    data class FillSelection(val color: Int) : MultiLayerCommand()
    data object CutSelection : MultiLayerCommand()

    // ── フィルター（アクティブレイヤーのみ） ──────────────────

    data class UnsharpMask(
        val radius: Int = 1, val amount: Float = 1f, val threshold: Int = 0,
    ) : ActiveLayerCommand()

    data class Mosaic(val blockSize: Int) : ActiveLayerCommand()
    data class Noise(val amount: Int, val monochrome: Boolean = true) : ActiveLayerCommand()
    data class Posterize(val levels: Int) : ActiveLayerCommand()
    data class Threshold(val threshold: Int) : ActiveLayerCommand()
    data object InvertColors : ActiveLayerCommand()
    data class MotionBlur(val angleDeg: Float, val distance: Int) : ActiveLayerCommand()

    data class Levels(
        val inBlack: Int, val inWhite: Int, val gamma: Float,
        val outBlack: Int, val outWhite: Int,
    ) : ActiveLayerCommand()

    data class ColorBalance(
        val cyanRed: Int, val magentaGreen: Int, val yellowBlue: Int,
    ) : ActiveLayerCommand()

    data class GradientMap(val gradientLut: IntArray) : ActiveLayerCommand()
}

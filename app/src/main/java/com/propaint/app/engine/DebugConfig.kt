package com.propaint.app.engine

import android.util.Log

/**
 * 構造化診断ログ基盤。CLAUDE.md 準拠。
 * DebugConfig.enableDiagnosticLog = true で有効化。
 */
object DebugConfig {
    @Volatile
    var enableDiagnosticLog: Boolean = false
}

/** PaintDebug タグ体系。CLAUDE.md 準拠。 */
object PaintDebug {
    const val Brush = "PaintDebug.Brush"
    const val Tile = "PaintDebug.Tile"
    const val Layer = "PaintDebug.Layer"
    const val Input = "PaintDebug.Input"
    const val GL = "PaintDebug.GL"
    const val Undo = "PaintDebug.Undo"
    const val Perf = "PaintDebug.Perf"
    const val ASSERT = "PaintDebug.ASSERT"

    /** ホットパスでの文字列生成コスト回避用 inline ログ */
    inline fun d(tag: String, message: () -> String) {
        if (DebugConfig.enableDiagnosticLog) {
            Log.d(tag, message())
        }
    }

    /** エラーログ（常に出力） */
    fun e(tag: String, message: String) {
        Log.e(tag, message)
    }

    /** アサーション違反ログ（常に出力） */
    fun assertFail(message: String) {
        Log.e(ASSERT, "[ASSERT FAIL] $message")
    }
}

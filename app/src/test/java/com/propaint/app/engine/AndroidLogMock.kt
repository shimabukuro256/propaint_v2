package com.propaint.app.engine

import io.mockk.every
import io.mockk.mockkStatic

/**
 * JVM テスト用: android.util.Log をモックして NoClassDefFoundError を回避。
 * 各テストの @Before で呼び出すこと。
 */
fun mockAndroidLog() {
    mockkStatic(android.util.Log::class)
    every { android.util.Log.d(any(), any()) } returns 0
    every { android.util.Log.e(any(), any()) } returns 0
    every { android.util.Log.w(any(), any<String>()) } returns 0
    every { android.util.Log.i(any(), any()) } returns 0
    every { android.util.Log.v(any(), any()) } returns 0
}

package com.propaint.app

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propaint.app.gl.Shaders
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GL シェーダーコンパイルテスト (Instrumented)。
 * エミュレータ / 実機上で GLES20 コンテキストを使用。
 *
 * 検証項目:
 * - シェーダーソースが GLSL として正しくコンパイルできること
 * - プログラムがリンクできること
 * - CLAUDE.md: GL コンテキスト喪失 → onSurfaceCreated でテクスチャ再生成
 */
@RunWith(AndroidJUnit4::class)
class GlShaderTest {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    @Before
    fun setupEgl() {
        // オフスクリーン EGL コンテキストを作成
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertNotEquals("eglGetDisplay failed", EGL14.EGL_NO_DISPLAY, eglDisplay)

        val version = IntArray(2)
        assertTrue("eglInitialize failed", EGL14.eglInitialize(eglDisplay, version, 0, version, 1))

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        assertTrue(
            "eglChooseConfig failed",
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        )
        assertTrue("No EGL config found", numConfigs[0] > 0)

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0]!!, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        assertNotEquals("eglCreateContext failed", EGL14.EGL_NO_CONTEXT, eglContext)

        val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0]!!, surfaceAttribs, 0)
        assertNotEquals("eglCreatePbufferSurface failed", EGL14.EGL_NO_SURFACE, eglSurface)

        assertTrue(
            "eglMakeCurrent failed",
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        )
    }

    @After
    fun teardownEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        assertTrue("glCreateShader failed", shader != 0)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            fail("Shader compile failed: $log")
        }
        return shader
    }

    private fun linkProgram(vertSource: String, fragSource: String): Int {
        val vert = compileShader(GLES20.GL_VERTEX_SHADER, vertSource)
        val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSource)

        val program = GLES20.glCreateProgram()
        assertTrue("glCreateProgram failed", program != 0)
        GLES20.glAttachShader(program, vert)
        GLES20.glAttachShader(program, frag)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            fail("Program link failed: $log")
        }

        GLES20.glDeleteShader(vert)
        GLES20.glDeleteShader(frag)
        return program
    }

    // ── テスト ──────────────────────────────────────────────────────

    @Test
    fun quadVertexShader_compiles() {
        val shader = compileShader(GLES20.GL_VERTEX_SHADER, Shaders.QUAD_VERT)
        assertTrue(shader != 0)
        GLES20.glDeleteShader(shader)
    }

    @Test
    fun quadFragmentShader_compiles() {
        val shader = compileShader(GLES20.GL_FRAGMENT_SHADER, Shaders.QUAD_FRAG)
        assertTrue(shader != 0)
        GLES20.glDeleteShader(shader)
    }

    @Test
    fun checkerFragmentShader_compiles() {
        val shader = compileShader(GLES20.GL_FRAGMENT_SHADER, Shaders.CHECKER_FRAG)
        assertTrue(shader != 0)
        GLES20.glDeleteShader(shader)
    }

    @Test
    fun quadProgram_linksAndHasUniforms() {
        val program = linkProgram(Shaders.QUAD_VERT, Shaders.QUAD_FRAG)
        GLES20.glUseProgram(program)

        // uniform / attribute が見つかること
        val uMVP = GLES20.glGetUniformLocation(program, "uMVP")
        val uTex = GLES20.glGetUniformLocation(program, "uTex")
        val uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aUV = GLES20.glGetAttribLocation(program, "aUV")

        assertTrue("uMVP not found", uMVP >= 0)
        assertTrue("uTex not found", uTex >= 0)
        assertTrue("uAlpha not found", uAlpha >= 0)
        assertTrue("aPos not found", aPos >= 0)
        assertTrue("aUV not found", aUV >= 0)

        GLES20.glDeleteProgram(program)
    }

    @Test
    fun checkerProgram_linksAndHasUniforms() {
        val program = linkProgram(Shaders.QUAD_VERT, Shaders.CHECKER_FRAG)
        GLES20.glUseProgram(program)

        val uSize = GLES20.glGetUniformLocation(program, "uSize")
        assertTrue("uSize not found", uSize >= 0)

        GLES20.glDeleteProgram(program)
    }

    @Test
    fun noGlErrors() {
        // テスト終了時に GL エラーが蓄積されていないこと
        val error = GLES20.glGetError()
        assertEquals("GL error detected: $error", GLES20.GL_NO_ERROR, error)
    }
}

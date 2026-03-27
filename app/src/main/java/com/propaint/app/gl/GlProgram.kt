package com.propaint.app.gl

import android.opengl.GLES20

class GlProgram(vertSrc: String, fragSrc: String) {
    val id: Int

    init {
        val vert = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vert)
        GLES20.glAttachShader(id, frag)
        GLES20.glLinkProgram(id)
        GLES20.glDeleteShader(vert)
        GLES20.glDeleteShader(frag)
    }

    fun use() = GLES20.glUseProgram(id)
    fun uniform(name: String): Int = GLES20.glGetUniformLocation(id, name)
    fun attrib(name: String): Int = GLES20.glGetAttribLocation(id, name)
    fun delete() = GLES20.glDeleteProgram(id)

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        return shader
    }
}

package com.indoorar.wayfinder.arcore.render

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal object GlUtil {
    fun program(vertexSrc: String, fragmentSrc: String): Int {
        val vs = shader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Program link failed: ${GLES20.glGetProgramInfoLog(program)}" }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun shader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data)
            position(0)
        }
}

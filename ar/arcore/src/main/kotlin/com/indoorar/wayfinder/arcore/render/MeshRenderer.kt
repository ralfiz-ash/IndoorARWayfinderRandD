package com.indoorar.wayfinder.arcore.render

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.FloatBuffer

/** Draws [Mesh]es with a single colour and simple directional + ambient lighting. */
class MeshRenderer {
    private var program = 0
    private var aPosition = 0
    private var aNormal = 0
    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0
    private val buffers = HashMap<Mesh, FloatBuffer>()
    private val mvp = FloatArray(16)
    private val vp = FloatArray(16)

    internal fun createOnGlThread() {
        buffers.clear() // Buffers are CPU-side, but a new context means a fresh start.
        program = GlUtil.program(VERTEX, FRAGMENT)
        aPosition = GLES20.glGetAttribLocation(program, "a_Position")
        aNormal = GLES20.glGetAttribLocation(program, "a_Normal")
        uMvp = GLES20.glGetUniformLocation(program, "u_Mvp")
        uModel = GLES20.glGetUniformLocation(program, "u_Model")
        uColor = GLES20.glGetUniformLocation(program, "u_Color")
    }

    internal fun setViewProjection(view: FloatArray, projection: FloatArray) {
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
    }

    private val clip = FloatArray(4)

    /**
     * Projects a session-space point to viewport pixels using the current view-projection.
     * Returns null if the point is behind the camera.
     */
    fun project(x: Float, y: Float, z: Float, width: Int, height: Int): FloatArray? {
        Matrix.multiplyMV(clip, 0, vp, 0, floatArrayOf(x, y, z, 1f), 0)
        if (clip[3] <= 1e-4f) return null
        val nx = clip[0] / clip[3]
        val ny = clip[1] / clip[3]
        return floatArrayOf((nx + 1f) / 2f * width, (1f - ny) / 2f * height)
    }

    /** @param color RGBA, 0..1. Alpha < 1 is blended. */
    fun draw(mesh: Mesh, model: FloatArray, color: FloatArray) {
        val buffer = buffers.getOrPut(mesh) { GlUtil.floatBuffer(mesh.vertexData) }
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform4fv(uColor, 1, color, 0)
        val translucent = color[3] < 1f
        if (translucent) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
        }
        buffer.position(0)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 24, buffer)
        buffer.position(3)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 24, buffer)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aNormal)
        if (translucent) {
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glDepthMask(true)
        }
    }

    private companion object {
        const val VERTEX = """
            uniform mat4 u_Mvp;
            uniform mat4 u_Model;
            attribute vec4 a_Position;
            attribute vec3 a_Normal;
            varying vec3 v_Normal;
            void main() {
                v_Normal = normalize((u_Model * vec4(a_Normal, 0.0)).xyz);
                gl_Position = u_Mvp * a_Position;
            }
        """
        const val FRAGMENT = """
            precision mediump float;
            uniform vec4 u_Color;
            varying vec3 v_Normal;
            void main() {
                vec3 light = normalize(vec3(0.3, 1.0, 0.4));
                float diffuse = abs(dot(normalize(v_Normal), light));
                gl_FragColor = vec4(u_Color.rgb * (0.45 + 0.55 * diffuse), u_Color.a);
            }
        """
    }
}

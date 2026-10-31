package com.personal.minivideooverlay

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws each frame into the encoder's input Surface:
 *  1. Background: the decoded video, center-cropped to 9:16 (no distortion, no bars).
 *  2. Overlay: the square photo at the fixed position from OverlayConfig.
 *
 * All methods must be called on the same thread that created this object.
 */
class GlCompositor(encoderSurface: Surface, overlay: Bitmap) {

    private val display: EGLDisplay
    private val context: EGLContext
    private val eglSurface: EGLSurface

    private val videoProgram: Int
    private val photoProgram: Int
    private val videoTexMatrixLoc: Int
    private val videoCropLoc: Int
    private val videoPosLoc: Int
    private val videoTexLoc: Int
    private val photoPosLoc: Int
    private val photoTexLoc: Int

    val videoTextureId: Int
    private val photoTextureId: Int

    private val quad: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD); position(0) }

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0
        ) { "No suitable EGL config" }
        val config = configs[0]!!

        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreateWindowSurface(
            display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }

        videoProgram = createProgram(VIDEO_VERTEX_SHADER, VIDEO_FRAGMENT_SHADER)
        photoProgram = createProgram(PHOTO_VERTEX_SHADER, PHOTO_FRAGMENT_SHADER)
        videoPosLoc = GLES20.glGetAttribLocation(videoProgram, "aPosition")
        videoTexLoc = GLES20.glGetAttribLocation(videoProgram, "aTexCoord")
        videoTexMatrixLoc = GLES20.glGetUniformLocation(videoProgram, "uSTMatrix")
        videoCropLoc = GLES20.glGetUniformLocation(videoProgram, "uCropScale")
        photoPosLoc = GLES20.glGetAttribLocation(photoProgram, "aPosition")
        photoTexLoc = GLES20.glGetAttribLocation(photoProgram, "aTexCoord")

        videoTextureId = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        photoTextureId = createTexture(GLES20.GL_TEXTURE_2D)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay, 0)
    }

    /**
     * @param texMatrix  transform from SurfaceTexture.getTransformMatrix
     * @param cropX/Y    texture-space scale that performs the center-crop to 9:16
     * @param ptsNs      presentation time in nanoseconds for the encoder
     */
    fun drawFrame(texMatrix: FloatArray, cropX: Float, cropY: Float, ptsNs: Long) {
        GLES20.glDisable(GLES20.GL_BLEND)

        // Background video: whole output canvas.
        GLES20.glViewport(0, 0, OverlayConfig.OUTPUT_WIDTH, OverlayConfig.OUTPUT_HEIGHT)
        drawVideoBackground(texMatrix, cropX, cropY)

        // Photo overlay. GL viewport origin is bottom-left, so convert the top-left Y.
        val glBottom = OverlayConfig.OUTPUT_HEIGHT - OverlayConfig.OVERLAY_Y - OverlayConfig.OVERLAY_SIZE
        GLES20.glViewport(
            OverlayConfig.OVERLAY_X, glBottom,
            OverlayConfig.OVERLAY_SIZE, OverlayConfig.OVERLAY_SIZE
        )
        drawPhotoOverlay()

        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed" }
    }

    fun release() {
        GLES20.glDeleteTextures(2, intArrayOf(videoTextureId, photoTextureId), 0)
        GLES20.glDeleteProgram(videoProgram)
        GLES20.glDeleteProgram(photoProgram)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    private fun drawVideoBackground(texMatrix: FloatArray, cropX: Float, cropY: Float) {
        GLES20.glUseProgram(videoProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniformMatrix4fv(videoTexMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glUniform2f(videoCropLoc, cropX, cropY)
        bindQuad(videoPosLoc, videoTexLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(videoPosLoc)
        GLES20.glDisableVertexAttribArray(videoTexLoc)
    }

    private fun drawPhotoOverlay() {
        GLES20.glUseProgram(photoProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTextureId)
        bindQuad(photoPosLoc, photoTexLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(photoPosLoc)
        GLES20.glDisableVertexAttribArray(photoTexLoc)
    }

    private fun bindQuad(posLoc: Int, texLoc: Int) {
        quad.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, quad)
        quad.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, quad)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(texLoc)
    }

    private fun createTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Program link failed: " + GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
        private const val STRIDE_BYTES = 4 * 4

        // x, y, u, v for a full-screen triangle strip. The viewport decides where it lands.
        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f,
             1f, -1f, 1f, 0f,
            -1f,  1f, 0f, 1f,
             1f,  1f, 1f, 1f
        )

        private const val VIDEO_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            uniform vec2 uCropScale;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec2 uv = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
                vTexCoord = (uv - 0.5) * uCropScale + 0.5;
            }
        """

        private const val VIDEO_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        private const val PHOTO_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        private const val PHOTO_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}

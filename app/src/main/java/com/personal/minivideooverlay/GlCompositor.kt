package com.personal.minivideooverlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** GPU compositor: opaque black canvas, full-width proportionally scaled video, full-canvas alpha PNG. */
class GlCompositor(
    encoderSurface: Surface,
    overlay: Bitmap,
    private val renderedHeightRatio: Float,
    private val rotationDegrees: Int
) {
    private val display: EGLDisplay
    private val context: EGLContext
    private val eglSurface: EGLSurface
    private val program: Int
    private val overlayProgram: Int
    private val videoPos: Int
    private val videoTex: Int
    private val videoMatrix: Int
    private val videoHeight: Int
    private val rotation: Int
    private val overlayPos: Int
    private val overlayTex: Int
    private val overlayOpacity: Int
    val videoTextureId: Int
    private val overlayTextureId: Int
    private val musicTextureId: Int
    private var musicNotificationKey: String? = null
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(QUAD); position(0) }

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "EGL display tidak tersedia" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize gagal" }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142, 1, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "EGL config tidak tersedia" }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "EGL context gagal dibuat" }
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "EGL surface gagal dibuat" }

        val maxTexture = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexture, 0)
        check(maxTexture[0] >= OverlayConfig.OUTPUT_WIDTH && maxTexture[0] >= OverlayConfig.OUTPUT_HEIGHT) {
            "GPU perangkat tidak mendukung texture Full HD (batas ${maxTexture[0]} px)."
        }
        program = createProgram(VERTEX, VIDEO_FRAGMENT)
        overlayProgram = createProgram(VERTEX, OVERLAY_FRAGMENT)
        videoPos = GLES20.glGetAttribLocation(program, "aPosition")
        videoTex = GLES20.glGetAttribLocation(program, "aTexCoord")
        videoMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
        videoHeight = GLES20.glGetUniformLocation(program, "uHeightRatio")
        rotation = GLES20.glGetUniformLocation(program, "uRotation")
        overlayPos = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        overlayTex = GLES20.glGetAttribLocation(overlayProgram, "aTexCoord")
        overlayOpacity = GLES20.glGetUniformLocation(overlayProgram, "uOpacity")
        videoTextureId = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        overlayTextureId = createTexture(GLES20.GL_TEXTURE_2D)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay, 0)
        musicTextureId = createTexture(GLES20.GL_TEXTURE_2D)
        val emptyNotification = Bitmap.createBitmap(OverlayConfig.OUTPUT_WIDTH, OverlayConfig.OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, musicTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, emptyNotification, 0)
        emptyNotification.recycle()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /** Updates the small top-left now-playing card only when its content changes. */
    fun updateMusicNotification(cue: MusicTrackCue?) {
        val key = cue?.let { "${it.title}\u0000${it.artist}" }
        if (key == musicNotificationKey) return
        musicNotificationKey = key
        val bitmap = Bitmap.createBitmap(OverlayConfig.OUTPUT_WIDTH, OverlayConfig.OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888)
        if (cue != null) {
            val canvas = Canvas(bitmap)
            val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(218, 10, 17, 27) }
            val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(82, 197, 255) }
            val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(105, 207, 255); textSize = 57f; typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 34f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(190, 205, 220); textSize = 25f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            canvas.drawRoundRect(RectF(42f, 42f, 690f, 164f), 18f, 18f, background)
            canvas.drawRoundRect(RectF(42f, 42f, 51f, 164f), 5f, 5f, accent)
            canvas.drawText("♫", 75f, 119f, iconPaint)
            canvas.drawText(ellipsize(cue.title, titlePaint, 500f), 150f, 94f, titlePaint)
            val artist = cue.artist.ifBlank { "NOW PLAYING" }
            canvas.drawText(ellipsize(artist, artistPaint, 500f), 150f, 132f, artistPaint)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, musicTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        bitmap.recycle()
    }

    private fun ellipsize(value: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(value) <= maxWidth) return value
        val suffix = "…"
        var end = value.length
        while (end > 0 && paint.measureText(value.substring(0, end) + suffix) > maxWidth) end--
        return value.substring(0, end).trimEnd() + suffix
    }

    fun drawFrame(texMatrix: FloatArray, ptsNs: Long, musicOpacity: Float = 1f) {
        GLES20.glViewport(0, 0, OverlayConfig.OUTPUT_WIDTH, OverlayConfig.OUTPUT_HEIGHT)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniformMatrix4fv(videoMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1f(videoHeight, renderedHeightRatio)
        GLES20.glUniform1i(rotation, ((rotationDegrees % 360) + 360) % 360)
        bind(videoPos, videoTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(videoPos)
        GLES20.glDisableVertexAttribArray(videoTex)

        // Premultiplied-independent straight-alpha blend: transparent PNG pixels reveal the video.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(overlayProgram)
        GLES20.glUniform1f(overlayOpacity, 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        bind(overlayPos, overlayTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(overlayPos)
        GLES20.glDisableVertexAttribArray(overlayTex)

        // The notification is a separate transparent layer, above both gameplay and user PNG.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(overlayProgram)
        GLES20.glUniform1f(overlayOpacity, musicOpacity.coerceIn(0f, 1f))
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, musicTextureId)
        bind(overlayPos, overlayTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(overlayPos)
        GLES20.glDisableVertexAttribArray(overlayTex)
        GLES20.glDisable(GLES20.GL_BLEND)
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers gagal" }
    }

    fun release() {
        runCatching {
            GLES20.glDeleteTextures(3, intArrayOf(videoTextureId, overlayTextureId, musicTextureId), 0)
            GLES20.glDeleteProgram(program)
            GLES20.glDeleteProgram(overlayProgram)
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    private fun bind(position: Int, tex: Int) {
        quad.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, quad)
        quad.position(2); GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(tex)
    }

    private fun createTexture(target: Int): Int {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(target, id[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun createProgram(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, source); GLES20.glCompileShader(id)
            val status = IntArray(1); GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(id) ?: "Shader compile gagal" }
            return id
        }
        val vs = shader(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs); GLES20.glAttachShader(p, fs); GLES20.glLinkProgram(p)
        val status = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(p) ?: "Shader link gagal" }
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs)
        return p
    }

    companion object {
        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f
        )
        private const val VERTEX = "attribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTex; void main(){gl_Position=aPosition;vTex=aTexCoord;}"
        private val VIDEO_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            uniform mat4 uSTMatrix;
            uniform float uHeightRatio;
            uniform int uRotation;
            varying vec2 vTex;
            void main() {
              float h = uHeightRatio;
              float y0 = (1.0 - h) * 0.5;
              if (vTex.y < y0 || vTex.y > y0 + h) { gl_FragColor = vec4(0.0,0.0,0.0,1.0); return; }
              vec2 p = vec2(vTex.x, (vTex.y-y0)/h);
              if (uRotation == 90) p = vec2(p.y, 1.0-p.x);
              else if (uRotation == 180) p = vec2(1.0-p.x, 1.0-p.y);
              else if (uRotation == 270) p = vec2(1.0-p.y, p.x);
              vec2 uv = (uSTMatrix * vec4(p,0.0,1.0)).xy;
              gl_FragColor = texture2D(sTexture, uv);
            }
        """.trimIndent()
        private const val OVERLAY_FRAGMENT = "precision mediump float; uniform sampler2D sTexture; uniform float uOpacity; varying vec2 vTex; void main(){gl_FragColor=texture2D(sTexture,vec2(vTex.x,1.0-vTex.y))*uOpacity;}"
    }
}

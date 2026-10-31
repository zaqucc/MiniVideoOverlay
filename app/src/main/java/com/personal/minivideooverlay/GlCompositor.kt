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
 * Renders one output frame into the encoder's input Surface, in four passes:
 *  1. Background video, center-cropped to 9:16, horizontal Gaussian blur  -> offscreen texture
 *  2. Vertical Gaussian blur of that texture                              -> screen
 *  3. Soft black shadow behind the overlay square                         -> screen (alpha blended)
 *  4. Sharp square photo at the fixed overlay rectangle                   -> screen
 *
 * All methods must be called on the thread that created this object.
 */
class GlCompositor(encoderSurface: Surface, overlay: Bitmap) {

    private val display: EGLDisplay
    private val context: EGLContext
    private val eglSurface: EGLSurface

    private val blurOesProgram: Int
    private val blurTexProgram: Int
    private val shadowProgram: Int
    private val photoProgram: Int

    private val oesPosLoc: Int
    private val oesTexLoc: Int
    private val oesTexMatrixLoc: Int
    private val oesCropLoc: Int
    private val oesDirLoc: Int

    private val texPosLoc: Int
    private val texTexLoc: Int
    private val texDirLoc: Int

    private val shadowPosLoc: Int
    private val shadowRectLoc: Int
    private val shadowBlurLoc: Int
    private val shadowAlphaLoc: Int

    private val photoPosLoc: Int
    private val photoTexLoc: Int

    val videoTextureId: Int
    private val photoTextureId: Int
    private val blurTextureId: Int
    private val blurFramebuffer: Int

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

        blurOesProgram = createProgram(PASSTHROUGH_VERTEX_SHADER, BLUR_OES_FRAGMENT_SHADER)
        blurTexProgram = createProgram(PASSTHROUGH_VERTEX_SHADER, BLUR_TEX_FRAGMENT_SHADER)
        shadowProgram = createProgram(SHADOW_VERTEX_SHADER, SHADOW_FRAGMENT_SHADER)
        photoProgram = createProgram(PHOTO_VERTEX_SHADER, PHOTO_FRAGMENT_SHADER)

        oesPosLoc = GLES20.glGetAttribLocation(blurOesProgram, "aPosition")
        oesTexLoc = GLES20.glGetAttribLocation(blurOesProgram, "aTexCoord")
        oesTexMatrixLoc = GLES20.glGetUniformLocation(blurOesProgram, "uSTMatrix")
        oesCropLoc = GLES20.glGetUniformLocation(blurOesProgram, "uCropScale")
        oesDirLoc = GLES20.glGetUniformLocation(blurOesProgram, "uDir")

        texPosLoc = GLES20.glGetAttribLocation(blurTexProgram, "aPosition")
        texTexLoc = GLES20.glGetAttribLocation(blurTexProgram, "aTexCoord")
        texDirLoc = GLES20.glGetUniformLocation(blurTexProgram, "uDir")

        shadowPosLoc = GLES20.glGetAttribLocation(shadowProgram, "aPosition")
        shadowRectLoc = GLES20.glGetUniformLocation(shadowProgram, "uRect")
        shadowBlurLoc = GLES20.glGetUniformLocation(shadowProgram, "uBlur")
        shadowAlphaLoc = GLES20.glGetUniformLocation(shadowProgram, "uAlpha")

        photoPosLoc = GLES20.glGetAttribLocation(photoProgram, "aPosition")
        photoTexLoc = GLES20.glGetAttribLocation(photoProgram, "aTexCoord")

        videoTextureId = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        photoTextureId = createTexture(GLES20.GL_TEXTURE_2D)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, overlay, 0)

        // Offscreen target for the horizontal blur pass, at full output size.
        blurTextureId = createTexture(GLES20.GL_TEXTURE_2D)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            OverlayConfig.OUTPUT_WIDTH, OverlayConfig.OUTPUT_HEIGHT, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        blurFramebuffer = fbo[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFramebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, blurTextureId, 0
        )
        check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
            "Blur framebuffer incomplete"
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * @param texMatrix  transform from SurfaceTexture.getTransformMatrix
     * @param cropX/Y    texture-space scale for the center-crop to 9:16
     * @param ptsNs      presentation time in nanoseconds for the encoder
     */
    fun drawFrame(texMatrix: FloatArray, cropX: Float, cropY: Float, ptsNs: Long) {
        val w = OverlayConfig.OUTPUT_WIDTH
        val h = OverlayConfig.OUTPUT_HEIGHT

        // 1. Horizontal blur of the cropped background video into the offscreen texture.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, blurFramebuffer)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glDisable(GLES20.GL_BLEND)
        drawHorizontalBlurPass(texMatrix, cropX, cropY)

        // 2. Vertical blur back to the screen (encoder surface).
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, w, h)
        drawVerticalBlurPass()

        // 3. Shadow behind the overlay square.
        drawShadow()

        // 4. Sharp photo overlay. GL viewport origin is bottom-left, so convert the top-left Y.
        val glBottom = h - OverlayConfig.OVERLAY_Y - OverlayConfig.OVERLAY_SIZE
        GLES20.glViewport(OverlayConfig.OVERLAY_X, glBottom, OverlayConfig.OVERLAY_SIZE, OverlayConfig.OVERLAY_SIZE)
        drawPhotoOverlay()

        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
        check(EGL14.eglSwapBuffers(display, eglSurface)) { "eglSwapBuffers failed" }
    }

    fun release() {
        GLES20.glDeleteFramebuffers(1, intArrayOf(blurFramebuffer), 0)
        GLES20.glDeleteTextures(3, intArrayOf(videoTextureId, photoTextureId, blurTextureId), 0)
        GLES20.glDeleteProgram(blurOesProgram)
        GLES20.glDeleteProgram(blurTexProgram)
        GLES20.glDeleteProgram(shadowProgram)
        GLES20.glDeleteProgram(photoProgram)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, eglSurface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }

    private fun drawHorizontalBlurPass(texMatrix: FloatArray, cropX: Float, cropY: Float) {
        GLES20.glUseProgram(blurOesProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
        GLES20.glUniformMatrix4fv(oesTexMatrixLoc, 1, false, texMatrix, 0)
        GLES20.glUniform2f(oesCropLoc, cropX, cropY)
        // Offset in output-frame space; the shader applies crop and the SurfaceTexture matrix per tap.
        val step = OverlayConfig.BACKGROUND_BLUR_TAP_SPACING_PX / OverlayConfig.OUTPUT_WIDTH
        GLES20.glUniform2f(oesDirLoc, step, 0f)
        bindQuad(oesPosLoc, oesTexLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(oesPosLoc)
        GLES20.glDisableVertexAttribArray(oesTexLoc)
    }

    private fun drawVerticalBlurPass() {
        GLES20.glUseProgram(blurTexProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, blurTextureId)
        GLES20.glUniform2f(texDirLoc, 0f, OverlayConfig.BACKGROUND_BLUR_TAP_SPACING_PX / OverlayConfig.OUTPUT_HEIGHT)
        bindQuad(texPosLoc, texTexLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(texPosLoc)
        GLES20.glDisableVertexAttribArray(texTexLoc)
    }

    private fun drawShadow() {
        val left = OverlayConfig.OVERLAY_X.toFloat()
        // Shadow is shifted downward in image space, which is lower Y in GL space.
        val bottom = (OverlayConfig.OUTPUT_HEIGHT - OverlayConfig.OVERLAY_Y - OverlayConfig.OVERLAY_SIZE).toFloat() -
            OverlayConfig.SHADOW_OFFSET_Y_PX
        val right = left + OverlayConfig.OVERLAY_SIZE
        val top = bottom + OverlayConfig.OVERLAY_SIZE

        GLES20.glUseProgram(shadowProgram)
        GLES20.glUniform4f(shadowRectLoc, left, bottom, right, top)
        GLES20.glUniform1f(shadowBlurLoc, OverlayConfig.SHADOW_BLUR_PX)
        GLES20.glUniform1f(shadowAlphaLoc, OverlayConfig.SHADOW_ALPHA)

        quad.position(0)
        GLES20.glVertexAttribPointer(shadowPosLoc, 2, GLES20.GL_FLOAT, false, STRIDE_BYTES, quad)
        GLES20.glEnableVertexAttribArray(shadowPosLoc)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(shadowPosLoc)
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

        // Bitmap row 0 is the image top, but GL texture coordinate v=0 is the bottom of the quad,
        // so the photo's V axis is flipped to keep it upright.
        private const val PHOTO_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
            }
        """

        private const val PASSTHROUGH_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """

        // 9-tap Gaussian, weights sum to 1.0. vTexCoord and uDir are in output-frame space.
        // Each tap is mapped to source texture space (crop, then SurfaceTexture matrix), so
        // rotation metadata cannot turn the blur into a one-direction smear.
        private const val BLUR_OES_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform samplerExternalOES sTexture;
            uniform mat4 uSTMatrix;
            uniform vec2 uCropScale;
            uniform vec2 uDir;
            varying vec2 vTexCoord;
            vec4 tap(vec2 offset) {
                vec2 d = (vTexCoord + offset - 0.5) * uCropScale + 0.5;
                return texture2D(sTexture, (uSTMatrix * vec4(d, 0.0, 1.0)).xy);
            }
            void main() {
                vec4 c = tap(vec2(0.0)) * 0.2270270270;
                c += (tap(uDir) + tap(-uDir)) * 0.1945945946;
                c += (tap(2.0 * uDir) + tap(-2.0 * uDir)) * 0.1216216216;
                c += (tap(3.0 * uDir) + tap(-3.0 * uDir)) * 0.0540540541;
                c += (tap(4.0 * uDir) + tap(-4.0 * uDir)) * 0.0162162162;
                gl_FragColor = c;
            }
        """

        private const val BLUR_TEX_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D sTexture;
            uniform vec2 uDir;
            varying vec2 vTexCoord;
            void main() {
                vec4 c = texture2D(sTexture, vTexCoord) * 0.2270270270;
                c += (texture2D(sTexture, vTexCoord + uDir) + texture2D(sTexture, vTexCoord - uDir)) * 0.1945945946;
                c += (texture2D(sTexture, vTexCoord + 2.0 * uDir) + texture2D(sTexture, vTexCoord - 2.0 * uDir)) * 0.1216216216;
                c += (texture2D(sTexture, vTexCoord + 3.0 * uDir) + texture2D(sTexture, vTexCoord - 3.0 * uDir)) * 0.0540540541;
                c += (texture2D(sTexture, vTexCoord + 4.0 * uDir) + texture2D(sTexture, vTexCoord - 4.0 * uDir)) * 0.0162162162;
                gl_FragColor = c;
            }
        """

        // Analytic soft rectangle: product of two smoothsteps in output pixel coordinates.
        private const val SHADOW_VERTEX_SHADER = """
            attribute vec4 aPosition;
            void main() {
                gl_Position = aPosition;
            }
        """

        private const val SHADOW_FRAGMENT_SHADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform vec4 uRect;   // left, bottom, right, top (GL pixel coordinates)
            uniform float uBlur;
            uniform float uAlpha;
            void main() {
                vec2 p = gl_FragCoord.xy;
                float ax = smoothstep(uRect.x - uBlur, uRect.x + uBlur, p.x)
                         * (1.0 - smoothstep(uRect.z - uBlur, uRect.z + uBlur, p.x));
                float ay = smoothstep(uRect.y - uBlur, uRect.y + uBlur, p.y)
                         * (1.0 - smoothstep(uRect.w - uBlur, uRect.w + uBlur, p.y));
                gl_FragColor = vec4(0.0, 0.0, 0.0, uAlpha * ax * ay);
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

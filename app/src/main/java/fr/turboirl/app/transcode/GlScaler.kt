package fr.turboirl.app.transcode

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import fr.turboirl.core.rtmp.Logger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * Decoder → SurfaceTexture → full-screen quad → encoder input surface. This is what lets the
 * output resolution differ from the camera's (and change on the fly). One GL thread owns the
 * EGL context; the encoder surface can be swapped at any time with [setOutput].
 */
class GlScaler(private val logger: Logger) {

    private val thread = HandlerThread("gl-scaler").apply { start() }
    private val handler = Handler(thread.looper)

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outWidth = 0
    private var outHeight = 0

    private var textureId = 0
    private var program = 0
    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private val texMatrix = FloatArray(16)
    private lateinit var surfaceTexture: SurfaceTexture

    /** Where the decoder renders. */
    lateinit var inputSurface: Surface
        private set

    /** Skip every other frame (half frame rate). */
    @Volatile var halfRate = false
    private var frameCount = 0L

    @Volatile var framesDrawn = 0L
        private set

    init {
        val ready = CountDownLatch(1)
        var error: Exception? = null
        handler.post {
            try {
                setup()
            } catch (e: Exception) {
                error = e
            }
            ready.countDown()
        }
        ready.await()
        error?.let { throw it }
    }

    private fun setup() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "pas d'affichage EGL" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "pas de config EGL" }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent" }

        program = buildProgram()
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        textureId = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener({ onFrame() }, handler)
        inputSurface = Surface(surfaceTexture)
    }

    /** Points the output at a (new) encoder surface, or detaches it with null. */
    fun setOutput(surface: Surface?, width: Int, height: Int) {
        val done = CountDownLatch(1)
        handler.post {
            try {
                if (window != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                    EGL14.eglDestroySurface(display, window)
                    window = EGL14.EGL_NO_SURFACE
                }
                if (surface != null) {
                    window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
                    if (window == EGL14.EGL_NO_SURFACE) {
                        logger.log("GL : surface d'encodeur refusée (0x${Integer.toHexString(EGL14.eglGetError())})")
                    }
                    outWidth = width
                    outHeight = height
                }
            } catch (e: Exception) {
                logger.log("GL : ${e.message}")
            }
            done.countDown()
        }
        done.await()
    }

    fun release() {
        handler.post {
            try {
                inputSurface.release()
                surfaceTexture.release()
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            } catch (_: Exception) {
            }
        }
        thread.quitSafely()
    }

    private fun onFrame() {
        val target = if (window != EGL14.EGL_NO_SURFACE) window else pbuffer
        if (!EGL14.eglMakeCurrent(display, target, target, context)) return
        try {
            surfaceTexture.updateTexImage()
        } catch (e: Exception) {
            return
        }
        frameCount++
        if (window == EGL14.EGL_NO_SURFACE || (halfRate && frameCount % 2 == 1L)) return
        surfaceTexture.getTransformMatrix(texMatrix)

        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, VERTICES)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, TEXCOORDS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)

        EGLExt.eglPresentationTimeANDROID(display, window, surfaceTexture.timestamp)
        if (EGL14.eglSwapBuffers(display, window)) framesDrawn++
    }

    private fun buildProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "programme GL : ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader GL : ${GLES20.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142

        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """

        val VERTICES: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        val TEXCOORDS: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun floats(vararg v: Float): FloatBuffer =
            ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(v)
                position(0)
            }
    }
}

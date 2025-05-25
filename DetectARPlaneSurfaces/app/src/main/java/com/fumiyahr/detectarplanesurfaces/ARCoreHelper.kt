package com.fumiyahr.detectarplanesurfaces

import android.opengl.GLES20
import android.opengl.GLES11Ext
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ARCoreHelper : GLSurfaceView.Renderer {
    private var session: Session? = null
    private var backgroundRenderer = BackgroundRenderer()
    private var planeRenderer = PlaneRenderer()
    
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    
    @Throws(UnavailableArcoreNotInstalledException::class, UnavailableUserDeclinedInstallationException::class)
    fun createSession(activity: android.app.Activity): Session {
        val session = Session(activity)
        
        // ARCoreの設定
        val config = Config(session)
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        session.configure(config)
        
        this.session = session
        return session
    }
    
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        
        // レンダラーの初期化
        try {
            backgroundRenderer.createOnGlThread()
            planeRenderer.createOnGlThread()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(0, width, height)
    }
    
    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        
        session?.let { session ->
            session.setCameraTextureName(backgroundRenderer.textureId)
            
            val frame = try {
                session.update()
            } catch (e: CameraNotAvailableException) {
                return
            }
            
            val camera = frame.camera
            
            // カメラビューマトリックスの取得
            camera.getViewMatrix(viewMatrix, 0)
            
            // プロジェクションマトリックスの取得
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
            
            // 背景のレンダリング
            backgroundRenderer.draw(frame)
            
            // 平面のレンダリング
            val planes = session.getAllTrackables(Plane::class.java)
            for (plane in planes) {
                if (plane.trackingState == TrackingState.TRACKING) {
                    planeRenderer.draw(plane, camera.displayOrientedPose, projectionMatrix)
                }
            }
        }
    }
    
    fun onResume() {
        session?.resume()
    }
    
    fun onPause() {
        session?.pause()
    }
}

// 背景レンダラー
class BackgroundRenderer {
    var textureId: Int = -1
        private set
    
    private var quadProgram: Int = 0
    private var quadPositionParam: Int = 0
    private var quadTexCoordParam: Int = 0
    private var quadVertexBuffer: FloatBuffer
    
    init {
        val coords = floatArrayOf(
            -1.0f, -1.0f, 0.0f, 0.0f,
            -1.0f, 1.0f, 0.0f, 1.0f,
            1.0f, -1.0f, 1.0f, 0.0f,
            1.0f, 1.0f, 1.0f, 1.0f
        )
        
        quadVertexBuffer = ByteBuffer.allocateDirect(coords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        quadVertexBuffer.put(coords).position(0)
    }
    
    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        
        val vertexShader = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()
        
        val fragmentShader = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """.trimIndent()
        
        quadProgram = createShaderProgram(vertexShader, fragmentShader)
        quadPositionParam = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordParam = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
    }
    
    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadVertexBuffer,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadVertexBuffer
            )
        }
        
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        
        GLES20.glUseProgram(quadProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        
        quadVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(quadPositionParam, 2, GLES20.GL_FLOAT, false, 16, quadVertexBuffer)
        GLES20.glEnableVertexAttribArray(quadPositionParam)
        
        quadVertexBuffer.position(2)
        GLES20.glVertexAttribPointer(quadTexCoordParam, 2, GLES20.GL_FLOAT, false, 16, quadVertexBuffer)
        GLES20.glEnableVertexAttribArray(quadTexCoordParam)
        
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        GLES20.glDisableVertexAttribArray(quadPositionParam)
        GLES20.glDisableVertexAttribArray(quadTexCoordParam)
        
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }
    
    private fun createShaderProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        
        return program
    }
    
    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}

// 平面レンダラー
class PlaneRenderer {
    private var planeProgram: Int = 0
    private var planePositionParam: Int = 0
    private var planeModelViewProjectionParam: Int = 0
    private var planeColorParam: Int = 0
    
    fun createOnGlThread() {
        val vertexShader = """
            uniform mat4 u_ModelViewProjection;
            attribute vec4 a_Position;
            void main() {
                gl_Position = u_ModelViewProjection * a_Position;
            }
        """.trimIndent()
        
        val fragmentShader = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
                gl_FragColor = u_Color;
            }
        """.trimIndent()
        
        planeProgram = createShaderProgram(vertexShader, fragmentShader)
        planePositionParam = GLES20.glGetAttribLocation(planeProgram, "a_Position")
        planeModelViewProjectionParam = GLES20.glGetUniformLocation(planeProgram, "u_ModelViewProjection")
        planeColorParam = GLES20.glGetUniformLocation(planeProgram, "u_Color")
    }
    
    fun draw(plane: Plane, cameraPose: Pose, projectionMatrix: FloatArray) {
        val polygon = plane.polygon
        if (polygon.remaining() == 0) return
        
        GLES20.glUseProgram(planeProgram)
        
        // 平面の色（半透明の青）
        GLES20.glUniform4f(planeColorParam, 0.0f, 0.5f, 1.0f, 0.5f)
        
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        // 平面の頂点データを準備
        val vertexCount = polygon.remaining() / 2
        val vertices = FloatArray(vertexCount * 3)
        
        polygon.rewind()
        for (i in 0 until vertexCount) {
            vertices[i * 3] = polygon.get()     // x
            vertices[i * 3 + 1] = 0.0f          // y (平面上なので0)
            vertices[i * 3 + 2] = polygon.get() // z
        }
        
        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexBuffer.put(vertices).position(0)
        
        // モデルビュープロジェクションマトリックスの計算
        val modelMatrix = FloatArray(16)
        val modelViewMatrix = FloatArray(16)
        val modelViewProjectionMatrix = FloatArray(16)
        
        plane.centerPose.toMatrix(modelMatrix, 0)
        Matrix.multiplyMM(modelViewMatrix, 0, cameraPose.inverse().toMatrix(), 0, modelMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0)
        
        GLES20.glUniformMatrix4fv(planeModelViewProjectionParam, 1, false, modelViewProjectionMatrix, 0)
        
        GLES20.glVertexAttribPointer(planePositionParam, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(planePositionParam)
        
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, vertexCount)
        
        GLES20.glDisableVertexAttribArray(planePositionParam)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
    
    private fun createShaderProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        
        return program
    }
    
    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }
}

// 拡張関数
private fun Pose.toMatrix(): FloatArray {
    val matrix = FloatArray(16)
    toMatrix(matrix, 0)
    return matrix
}

private fun Pose.inverse(): Pose {
    return extractTranslation().compose(extractRotation().inverse())
}

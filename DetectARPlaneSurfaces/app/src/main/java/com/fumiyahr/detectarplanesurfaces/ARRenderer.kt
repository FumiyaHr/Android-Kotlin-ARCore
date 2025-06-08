package com.fumiyahr.detectarplanesurfaces

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ARRenderer : GLSurfaceView.Renderer {
    private var session: Session? = null
    private var backgroundRenderer: BackgroundRenderer? = null
    private var planeRenderer: PlaneRenderer? = null
    private var isGLInitialized = false
    
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val anchorMatrix = FloatArray(16)

    fun onSurfaceCreated(session: Session) {
        this.session = session
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        try {
            backgroundRenderer = BackgroundRenderer()
            backgroundRenderer?.createOnGlThread()
            
            planeRenderer = PlaneRenderer()
            planeRenderer?.createOnGlThread()
            
            isGLInitialized = true
            android.util.Log.d("ARRenderer", "OpenGL ES initialized successfully")
            
        } catch (e: Exception) {
            android.util.Log.e("ARRenderer", "Failed to initialize OpenGL ES", e)
            e.printStackTrace()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        session?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!isGLInitialized) {
            return
        }
        
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        session?.let { session ->
            if (backgroundRenderer?.textureId == 0) {
                return@let
            }
            
            try {
                session.setCameraTextureName(backgroundRenderer?.textureId ?: 0)
                
                val frame = session.update()
                val camera = frame.camera

                // Always draw background first, regardless of tracking state
                backgroundRenderer?.draw(frame)

                if (camera.trackingState == TrackingState.TRACKING) {
                    // Get projection and view matrices
                    camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f)
                    camera.getViewMatrix(viewMatrix, 0)

                    // Handle planes
                    val planes = session.getAllTrackables(Plane::class.java)
                    var planeCount = 0
                    for (plane in planes) {
                        if (plane.trackingState == TrackingState.TRACKING && 
                            plane.subsumedBy == null) {
                            planeCount++
                            
                            // Draw plane
                            planeRenderer?.drawPlane(
                                plane,
                                camera.pose,
                                projectionMatrix,
                                viewMatrix
                            )
                        }
                    }
                    // Debug: Log plane count occasionally
                    if (System.currentTimeMillis() % 2000 < 50) { // Every 2 seconds
                        android.util.Log.d("ARRenderer", "Detected planes: $planeCount")
                    }
                }
            } catch (e: CameraNotAvailableException) {
                android.util.Log.e("ARRenderer", "Camera not available", e)
                e.printStackTrace()
            } catch (e: Exception) {
                android.util.Log.e("ARRenderer", "Rendering error", e)
                e.printStackTrace()
            }
        }
    }
}

class BackgroundRenderer {
    private var quadProgram = 0
    private var quadPositionParam = 0
    private var quadTexCoordParam = 0
    private var quadSplitterUniform = 0
    private var quadTexture = 0
    private var quadVertices: FloatBuffer? = null
    private var quadTexCoord: FloatBuffer? = null
    private var quadIndices: FloatBuffer? = null

    var textureId = 0
        private set

    companion object {
        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,
            +1.0f, -1.0f,
            -1.0f, +1.0f,
            +1.0f, +1.0f
        )

        private val QUAD_TEXCOORDS = floatArrayOf(
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
        )

        private const val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """
    }

    fun createOnGlThread() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textureId)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        quadProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(quadProgram, vertexShader)
        GLES20.glAttachShader(quadProgram, fragmentShader)
        GLES20.glLinkProgram(quadProgram)
        GLES20.glUseProgram(quadProgram)

        quadPositionParam = GLES20.glGetAttribLocation(quadProgram, "a_Position")
        quadTexCoordParam = GLES20.glGetAttribLocation(quadProgram, "a_TexCoord")
        quadTexture = GLES20.glGetUniformLocation(quadProgram, "sTexture")

        quadVertices = ByteBuffer.allocateDirect(QUAD_COORDS.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        quadVertices?.put(QUAD_COORDS)
        quadVertices?.position(0)

        quadTexCoord = ByteBuffer.allocateDirect(QUAD_TEXCOORDS.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        quadTexCoord?.put(QUAD_TEXCOORDS)
        quadTexCoord?.position(0)
    }

    fun draw(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadVertices,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoord
            )
        }

        if (frame.timestamp == 0L) return

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        GLES20.glUseProgram(quadProgram)
        GLES20.glUniform1i(quadTexture, 0)

        GLES20.glVertexAttribPointer(quadPositionParam, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glVertexAttribPointer(quadTexCoordParam, 2, GLES20.GL_FLOAT, false, 0, quadTexCoord)

        GLES20.glEnableVertexAttribArray(quadPositionParam)
        GLES20.glEnableVertexAttribArray(quadTexCoordParam)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(quadPositionParam)
        GLES20.glDisableVertexAttribArray(quadTexCoordParam)

        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}

class PlaneRenderer {
    private var planeProgram = 0
    private var planeXZPositionAlphaAttribute = 0
    private var planeModelUniform = 0
    private var planeModelViewProjectionUniform = 0
    private var planeColorUniform = 0
    private var planeDotSizeUniform = 0

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 u_Model;
            uniform mat4 u_ModelViewProjection;

            attribute vec4 a_XZPositionAlpha;
            varying vec3 v_TexCoordAlpha;

            void main() {
                vec4 local_pos = vec4(a_XZPositionAlpha.x, 0.0, a_XZPositionAlpha.y, 1.0);
                gl_Position = u_ModelViewProjection * local_pos;
                v_TexCoordAlpha = vec3(a_XZPositionAlpha.xy, a_XZPositionAlpha.z);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision highp float;
            uniform vec3 u_PlaneColor;
            uniform float u_DotSize;
            varying vec3 v_TexCoordAlpha;

            void main() {
                // Create a grid pattern
                vec2 uv = v_TexCoordAlpha.xy * 10.0; // Scale up for more grid lines
                vec2 grid = abs(fract(uv - 0.5) - 0.5) / fwidth(uv);
                float line = min(grid.x, grid.y);
                float alpha = 1.0 - min(line, 1.0);
                
                // Add some base alpha for the plane
                alpha = max(alpha * 0.8, 0.2);
                
                gl_FragColor = vec4(u_PlaneColor, alpha * v_TexCoordAlpha.z);
            }
        """
    }

    fun createOnGlThread() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        planeProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(planeProgram, vertexShader)
        GLES20.glAttachShader(planeProgram, fragmentShader)
        GLES20.glLinkProgram(planeProgram)

        planeXZPositionAlphaAttribute = GLES20.glGetAttribLocation(planeProgram, "a_XZPositionAlpha")
        planeModelUniform = GLES20.glGetUniformLocation(planeProgram, "u_Model")
        planeModelViewProjectionUniform = GLES20.glGetUniformLocation(planeProgram, "u_ModelViewProjection")
        planeColorUniform = GLES20.glGetUniformLocation(planeProgram, "u_PlaneColor")
        planeDotSizeUniform = GLES20.glGetUniformLocation(planeProgram, "u_DotSize")
    }

    fun drawPlane(
        plane: Plane,
        cameraPose: Pose,
        projectionMatrix: FloatArray,
        viewMatrix: FloatArray
    ) {
        val planeMatrix = FloatArray(16)
        plane.centerPose.toMatrix(planeMatrix, 0)

        val modelViewProjectionMatrix = FloatArray(16)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, viewMatrix, 0, planeMatrix, 0)
        Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewProjectionMatrix, 0)

        // Create plane geometry using actual polygon
        val vertexBuffer = createPlaneVertexBuffer(plane)
        val vertexCount = vertexBuffer.remaining() / 3

        if (vertexCount == 0) return

        GLES20.glUseProgram(planeProgram)
        GLES20.glUniformMatrix4fv(planeModelUniform, 1, false, planeMatrix, 0)
        GLES20.glUniformMatrix4fv(planeModelViewProjectionUniform, 1, false, modelViewProjectionMatrix, 0)
        GLES20.glUniform3f(planeColorUniform, 0.3f, 0.8f, 0.3f) // Green color
        GLES20.glUniform1f(planeDotSizeUniform, 0.15f)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)

        GLES20.glVertexAttribPointer(planeXZPositionAlphaAttribute, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(planeXZPositionAlphaAttribute)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(planeXZPositionAlphaAttribute)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
    }
    
    private fun createPlaneVertexBuffer(plane: Plane): FloatBuffer {
        val polygon = plane.polygon
        val vertices = mutableListOf<Float>()

        polygon.rewind()
        val polygonVertices = FloatArray(polygon.remaining())
        polygon.get(polygonVertices)

        // Create triangulated mesh from polygon
        val numVertices = polygonVertices.size / 2
        if (numVertices < 3) {
            return ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }

        // Fan triangulation from center
        for (i in 0 until numVertices - 1) {
            val i1 = (i + 1) % numVertices
            
            // Triangle vertices
            vertices.addAll(listOf(
                polygonVertices[0], polygonVertices[1], 0.6f,  // First vertex
                polygonVertices[i * 2], polygonVertices[i * 2 + 1], 0.6f,  // Current vertex
                polygonVertices[i1 * 2], polygonVertices[i1 * 2 + 1], 0.6f   // Next vertex
            ))
        }

        val buffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(vertices.toFloatArray())
        buffer.position(0)
        return buffer
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}


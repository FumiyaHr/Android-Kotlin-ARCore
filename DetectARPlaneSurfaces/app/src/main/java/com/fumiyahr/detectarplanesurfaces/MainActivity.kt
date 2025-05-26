package com.fumiyahr.detectarplanesurfaces

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.fumiyahr.detectarplanesurfaces.ui.theme.DetectARPlaneSurfacesTheme
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : ComponentActivity() {
    private var session: Session? = null
    private lateinit var glSurfaceView: GLSurfaceView
    private var userRequestedInstall = true
    private val CAMERA_PERMISSION_CODE = 0
    private var cameraPermissionGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DetectARPlaneSurfacesTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Check and request camera permission
                    LaunchedEffect(Unit) {
                        if (!checkCameraPermission()) {
                            requestCameraPermission()
                        } else {
                            cameraPermissionGranted.value = true
                        }
                    }

                    if (cameraPermissionGranted.value) {
                        ARCoreApp()
                    } else {
                        // Optionally, show a message to the user that camera permission is required
                        Toast.makeText(this@MainActivity, "Camera permission is required to use AR features.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                cameraPermissionGranted.value = true
            } else {
                Toast.makeText(
                    this,
                    "Camera permission denied. AR features will not be available.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Composable
    fun ARCoreApp() {
        AndroidView(
            factory = { context ->
                glSurfaceView = GLSurfaceView(context).apply {
                    setEGLContextClientVersion(2)
                    setRenderer(MyRenderer())
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
                glSurfaceView
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    override fun onResume() {
        super.onResume()

        if (!cameraPermissionGranted.value) {
            return // Don't proceed if camera permission is not granted
        }

        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, userRequestedInstall)) {
                    ArCoreApk.InstallStatus.INSTALLED -> {
                        session = Session(this)
                        val config = Config(session)
                        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                        session?.configure(config)
                    }
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        userRequestedInstall = false
                        return
                    }
                }
            } catch (e: UnavailableSdkTooOldException) {
                Toast.makeText(this, "ARCore SDK is too old.", Toast.LENGTH_LONG).show()
            } catch (e: UnavailableDeviceNotCompatibleException) {
                Toast.makeText(this, "This device does not support AR.", Toast.LENGTH_LONG).show()
            } catch (e: UnavailableApkTooOldException) {
                Toast.makeText(this, "Please update ARCore.", Toast.LENGTH_LONG).show()
            } catch (e: UnavailableUserDeclinedInstallationException) {
                Toast.makeText(this, "Please install ARCore.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to create AR session: $e", Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            session?.resume()
            glSurfaceView.onResume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(this, "Camera not available. Try restarting the app.", Toast.LENGTH_LONG).show()
            session = null
            return
        }
    }

    override fun onPause() {
        super.onPause()
        session?.pause()
        if (this::glSurfaceView.isInitialized) {
            glSurfaceView.onPause()
        }
    }

    inner class MyRenderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // Set the background clear color to black.
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            if (width == 0 || height == 0) return // Avoid division by zero or invalid viewport
            GLES20.glViewport(0, 0, width, height)
            session?.setDisplayGeometry(resources.configuration.orientation, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

            session?.let { sess ->
                try {
                    sess.setCameraTextureName(0) // Dummy texture ID
                    val frame: Frame = sess.update()
                    val camera = frame.camera

                    // If tracking is not PAUSED, draw background.
                    if (camera.trackingState == TrackingState.TRACKING) {
                        // Get projection matrix.
                        val projmtx = FloatArray(16)
                        camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f)

                        // Get camera matrix and draw.
                        val viewmtx = FloatArray(16)
                        camera.getViewMatrix(viewmtx, 0)

                        // Visualize planes.
                        val planes = sess.getAllTrackables(Plane::class.java)
                        for (plane in planes) {
                            if (plane.trackingState == TrackingState.TRACKING && plane.subsumedBy == null) {
                                // plane.centerPose と plane.extentX, plane.extentZ を使用して平面を描画します。
                                // ここでは簡略化のため、ログ出力のみ行います。
                                System.out.println("Detected plane: " + plane.centerPose.toString())
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // Avoid crashing the application due to unhandled exceptions.
                    android.util.Log.e("MainActivity", "Exception on the OpenGL thread", t)
                }
            }
        }
    }
}
package com.fumiyahr.detectarplanesurfaces

import android.Manifest
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.exceptions.*

class MainActivity : ComponentActivity() {
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var arCoreHelper: ARCoreHelper
    private var session: Session? = null
    private var installRequested = false
    
    // カメラ権限のリクエストランチャー
    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            createArSession()
        } else {
            Toast.makeText(this, "Camera permission is required for AR", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // GLSurfaceViewの設定
        surfaceView = GLSurfaceView(this)
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        
        arCoreHelper = ARCoreHelper()
        surfaceView.setRenderer(arCoreHelper)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        
        setContentView(surfaceView)
    }
    
    override fun onResume() {
        super.onResume()
        
        // ARCoreがインストールされているかチェック
        when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                installRequested = true
                return
            }
            ArCoreApk.InstallStatus.INSTALLED -> {
                // ARCoreがインストール済み
            }
        }
        
        // カメラ権限をチェック
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        
        createArSession()
    }
    
    private fun createArSession() {
        try {
            if (session == null) {
                session = arCoreHelper.createSession(this)
            }
            session?.resume()
            arCoreHelper.onResume()
            surfaceView.onResume()
        } catch (e: UnavailableArcoreNotInstalledException) {
            Toast.makeText(this, "Please install ARCore", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: UnavailableUserDeclinedInstallationException) {
            Toast.makeText(this, "ARCore installation was declined", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: UnavailableApkTooOldException) {
            Toast.makeText(this, "Please update ARCore", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: UnavailableSdkTooOldException) {
            Toast.makeText(this, "Please update this app", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: UnavailableDeviceNotCompatibleException) {
            Toast.makeText(this, "This device does not support AR", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to create AR session", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        arCoreHelper.onPause()
        session?.pause()
    }
    
    override fun onDestroy() {
        session?.close()
        super.onDestroy()
    }
}
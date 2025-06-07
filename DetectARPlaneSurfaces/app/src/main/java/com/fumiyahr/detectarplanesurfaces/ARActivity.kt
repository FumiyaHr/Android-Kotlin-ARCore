package com.fumiyahr.detectarplanesurfaces

import android.os.Bundle
import android.view.SurfaceView
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.Config
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.*
import android.widget.Toast

class ARActivity : AppCompatActivity() {
    private var arSession: Session? = null
    private lateinit var surfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
    }

    override fun onResume() {
        super.onResume()
        // カメラパーミッションの確認とリクエスト
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 0)
            return
        }
        try {
            if (arSession == null) {
                when (ArCoreApk.getInstance().requestInstall(this, true)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> return
                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }
                arSession = Session(this)
                val config = Config(arSession)
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
                arSession!!.configure(config)
            }
            arSession?.resume()
        } catch (e: UnavailableException) {
            Toast.makeText(this, "ARCore利用不可: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        arSession?.pause()
    }
}

package com.kitzurim.app

import android.app.Application
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Torch.init(this)
    }
}

/** הפנס: זוכר אם הוא דולק, כדי שלחיצה אחת תדליק ולחיצה נוספת תכבה */
object Torch {
    private var on = false
    private var cameraId: String? = null

    fun init(ctx: Context) {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return
        cameraId = try {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        }
        cm.registerTorchCallback(object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId) on = enabled
            }
        }, Handler(Looper.getMainLooper()))
    }

    /** true = נדלק, false = כבה, null = אין פנס או שהמצלמה תפוסה */
    fun toggle(ctx: Context): Boolean? {
        val id = cameraId ?: return null
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return null
        return try {
            cm.setTorchMode(id, !on)
            on = !on
            on
        } catch (_: Exception) {
            null
        }
    }
}

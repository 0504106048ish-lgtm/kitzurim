package com.kitzurim.app

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.util.Locale
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.UUID

object QuietPlaces {
    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** רושם מחדש את כל המקומות אצל שירות המיקום של הטלפון */
    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        val app = ctx.applicationContext
        val client = LocationServices.getGeofencingClient(app)
        val pi = pendingIntent(app)
        client.removeGeofences(pi).addOnCompleteListener {
            val places = AutoPrefs.places(app)
            if (!AutoPrefs.quietEnabled(app) || places.isEmpty()) return@addOnCompleteListener
            val fences = places.map {
                Geofence.Builder()
                    .setRequestId(it.id)
                    .setCircularRegion(it.lat, it.lng, it.radius)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                    .build()
            }
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(fences)
                .build()
            try {
                client.addGeofences(request, pi)
            } catch (_: SecurityException) {
            }
        }
    }

    /** שומר את המקום שבו אתה נמצא עכשיו */
    @SuppressLint("MissingPermission")
    fun addCurrentPlace(ctx: Context, name: String, radius: Float, done: (Boolean) -> Unit) {
        try {
            LocationServices.getFusedLocationProviderClient(ctx)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc == null) {
                        done(false)
                    } else {
                        AutoPrefs.addPlace(
                            ctx,
                            Place(UUID.randomUUID().toString(), name, loc.latitude, loc.longitude, radius),
                        )
                        register(ctx)
                        done(true)
                    }
                }
                .addOnFailureListener { done(false) }
        } catch (_: SecurityException) {
            done(false)
        }
    }

    /** שומר מקום לפי כתובת או שם (למשל "בית כנסת הגדול, בני ברק"), בלי שתצטרך להיות שם */
    fun addByAddress(ctx: Context, name: String, address: String, radius: Float, done: (Boolean) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val found = try {
                @Suppress("DEPRECATION")
                Geocoder(ctx, Locale("he")).getFromLocationName(address, 1)?.firstOrNull()
            } catch (_: Exception) {
                null
            }
            main.post {
                if (found == null) {
                    done(false)
                } else {
                    AutoPrefs.addPlace(
                        ctx,
                        Place(UUID.randomUUID().toString(), name, found.latitude, found.longitude, radius),
                    )
                    register(ctx)
                    done(true)
                }
            }
        }.start()
    }

    fun dndAllowed(ctx: Context): Boolean =
        ctx.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

    /** מחליף מצב צלצול. בלי אישור "נא לא להפריע" אי אפשר שקט מלא, אז עוברים לרטט */
    fun setRinger(ctx: Context, mode: Int) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val target = if (mode == AudioManager.RINGER_MODE_SILENT && !dndAllowed(ctx)) {
            AudioManager.RINGER_MODE_VIBRATE
        } else {
            mode
        }
        try {
            am.ringerMode = target
        } catch (_: SecurityException) {
        }
    }
}

/** נכנסת למקום שקט או יצאת ממנו */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!AutoPrefs.quietEnabled(ctx)) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        val ids = event.triggeringGeofences?.map { it.requestId } ?: emptyList()
        val name = AutoPrefs.places(ctx).firstOrNull { it.id in ids }?.name ?: "מקום שקט"
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> {
                if (!AutoPrefs.inQuietPlace(ctx)) AutoPrefs.enterQuiet(ctx, am.ringerMode)
                QuietPlaces.setRinger(ctx, AudioManager.RINGER_MODE_SILENT)
                Actions.toast(ctx, "🤫 $name: הטלפון בשקט")
                ActivityLog.add(ctx, "🤫", "השתקתי ב$name")
            }
            Geofence.GEOFENCE_TRANSITION_EXIT -> {
                if (AutoPrefs.inQuietPlace(ctx)) {
                    QuietPlaces.setRinger(ctx, AutoPrefs.savedRinger(ctx))
                    AutoPrefs.leaveQuiet(ctx)
                    Actions.toast(ctx, "🔔 יצאת מ$name: הצלצול חזר")
                    ActivityLog.add(ctx, "🔔", "יצאת מ$name, הצלצול חזר")
                }
            }
        }
    }
}

/** אחרי הפעלה מחדש של הטלפון או עדכון של האפליקציה, המקומות נרשמים שוב */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        QuietPlaces.register(ctx)
        ScheduledQuiet.apply(ctx)
        Reminders.register(ctx)
        PlaceAlerts.register(ctx)
    }
}

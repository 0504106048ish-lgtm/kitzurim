package com.kitzurim.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import java.util.UUID

/** "תזכיר לי לקנות חלב כשאני ליד הסופר": התזכורת קופצת כשמגיעים למקום, ואז נמחקת */
object Reminders {
    private const val RADIUS_METERS = 200f

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 2, Intent(ctx, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** רושם מחדש את כל התזכורות אצל שירות המיקום */
    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        val app = ctx.applicationContext
        val client = LocationServices.getGeofencingClient(app)
        val pi = pendingIntent(app)
        client.removeGeofences(pi).addOnCompleteListener {
            val list = AutoPrefs.reminders(app)
            if (list.isEmpty()) return@addOnCompleteListener
            val fences = list.map {
                Geofence.Builder()
                    .setRequestId(it.id)
                    .setCircularRegion(it.lat, it.lng, RADIUS_METERS)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
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

    fun add(ctx: Context, text: String, place: String, lat: Double, lng: Double) {
        AutoPrefs.addReminder(ctx, PlaceReminder(UUID.randomUUID().toString(), text, place, lat, lng))
        register(ctx)
    }

    fun remove(ctx: Context, id: String) {
        AutoPrefs.removeReminder(ctx, id)
        register(ctx)
    }

    /** תזכורת למקום שבו אתה נמצא עכשיו */
    @SuppressLint("MissingPermission")
    fun addHere(ctx: Context, text: String, place: String, done: (Boolean) -> Unit) {
        try {
            LocationServices.getFusedLocationProviderClient(ctx)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc == null) done(false) else {
                        add(ctx, text, place, loc.latitude, loc.longitude)
                        done(true)
                    }
                }
                .addOnFailureListener { done(false) }
        } catch (_: SecurityException) {
            done(false)
        }
    }

    /**
     * מוצא את המקום לפי שם: קודם בין המקומות ששמרת (שקט לפי מקום) ובכתובת הבית,
     * ואחרת מחפש כתובת או שם של מקום (למשל "שופרסל רמות ירושלים").
     * התשובה חוזרת ב-done (או null אם לא נמצא).
     */
    fun findPlace(ctx: Context, query: String, done: (Triple<String, Double, Double>?) -> Unit) {
        val q = query.trim()
        AutoPrefs.places(ctx).firstOrNull { it.name.contains(q) || q.contains(it.name) }?.let {
            done(Triple(it.name, it.lat, it.lng))
            return
        }
        val search = if ((q == "בית" || q == "הבית") && Prefs.home(ctx).isNotBlank()) Prefs.home(ctx) else q
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = try {
                @Suppress("DEPRECATION")
                Geocoder(ctx, Locale("he")).getFromLocationName(search, 1)?.firstOrNull()
            } catch (_: Exception) {
                null
            }
            main.post { done(result?.let { Triple(q, it.latitude, it.longitude) }) }
        }.start()
    }

    /** תזכורת למקום לפי שם או כתובת */
    fun addByName(ctx: Context, text: String, placeQuery: String, done: (Boolean) -> Unit) {
        findPlace(ctx, placeQuery) { found ->
            if (found == null) {
                done(false)
            } else {
                add(ctx, text, found.first, found.second, found.third)
                done(true)
            }
        }
    }
}

/** הגעת למקום של תזכורת */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val ids = event.triggeringGeofences?.map { it.requestId } ?: return
        AutoPrefs.reminders(ctx).filter { it.id in ids }.forEach { r ->
            Notify.post(
                ctx, r.id.hashCode(),
                "📍 הגעת ל${r.place}",
                r.text,
                Intent(ctx, HomeActivity::class.java),
            )
            AutoPrefs.removeReminder(ctx, r.id)
        }
        Reminders.register(ctx)
    }
}

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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** התראה בכל פעם שמגיעים למקום או עוזבים אותו (לא נמחקת אחרי פעם אחת, בשונה מתזכורת) */
data class PlaceAlert(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radius: Float,
    val onArrive: Boolean,
    val onLeave: Boolean,
    val text: String,
)

object PlaceAlerts {
    private fun sp(c: Context) = c.getSharedPreferences("kitzurim_alerts", Context.MODE_PRIVATE)

    fun all(c: Context): List<PlaceAlert> = try {
        val a = JSONArray(sp(c).getString("alerts", "[]"))
        List(a.length()) {
            val o = a.getJSONObject(it)
            PlaceAlert(
                o.getString("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lng"),
                o.optDouble("r", 200.0).toFloat(), o.optBoolean("in", true), o.optBoolean("out", false),
                o.optString("text"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun save(c: Context, list: List<PlaceAlert>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject().put("id", it.id).put("name", it.name).put("lat", it.lat).put("lng", it.lng)
                    .put("r", it.radius.toDouble()).put("in", it.onArrive).put("out", it.onLeave).put("text", it.text)
            )
        }
        sp(c).edit().putString("alerts", a.toString()).apply()
        register(c)
    }

    fun add(c: Context, a: PlaceAlert) = save(c, all(c) + a)
    fun update(c: Context, a: PlaceAlert) = save(c, all(c).map { if (it.id == a.id) a else it })
    fun remove(c: Context, id: String) = save(c, all(c).filter { it.id != id })

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 3, Intent(ctx, PlaceAlertReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** רושם מחדש את כל ההתראות אצל שירות המיקום */
    @SuppressLint("MissingPermission")
    fun register(ctx: Context) {
        val app = ctx.applicationContext
        val client = LocationServices.getGeofencingClient(app)
        val pi = pendingIntent(app)
        client.removeGeofences(pi).addOnCompleteListener {
            val list = all(app).filter { it.onArrive || it.onLeave }
            if (list.isEmpty()) return@addOnCompleteListener
            val fences = list.map {
                var types = 0
                if (it.onArrive) types = types or Geofence.GEOFENCE_TRANSITION_ENTER
                if (it.onLeave) types = types or Geofence.GEOFENCE_TRANSITION_EXIT
                Geofence.Builder()
                    .setRequestId(it.id)
                    .setCircularRegion(it.lat, it.lng, it.radius)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(types)
                    .build()
            }
            // בלי התראה מיד ברגע ההוספה: רק כשבאמת מגיעים או עוזבים
            val request = GeofencingRequest.Builder()
                .setInitialTrigger(0)
                .addGeofences(fences)
                .build()
            try {
                client.addGeofences(request, pi)
            } catch (_: SecurityException) {
            }
        }
    }

    /** התראה למקום לפי כתובת או שם */
    fun addByAddress(
        ctx: Context, name: String, address: String, radius: Float,
        arrive: Boolean, leave: Boolean, text: String, done: (Boolean) -> Unit,
    ) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val found = try {
                @Suppress("DEPRECATION")
                Geocoder(ctx, Locale("he")).getFromLocationName(address, 1)?.firstOrNull()
            } catch (_: Exception) {
                null
            }
            main.post {
                if (found == null) done(false) else {
                    add(ctx, PlaceAlert(UUID.randomUUID().toString(), name, found.latitude, found.longitude, radius, arrive, leave, text))
                    done(true)
                }
            }
        }.start()
    }

    /** התראה למקום שבו אתה נמצא עכשיו */
    @SuppressLint("MissingPermission")
    fun addHere(
        ctx: Context, name: String, radius: Float,
        arrive: Boolean, leave: Boolean, text: String, done: (Boolean) -> Unit,
    ) {
        try {
            LocationServices.getFusedLocationProviderClient(ctx)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc == null) done(false) else {
                        add(ctx, PlaceAlert(UUID.randomUUID().toString(), name, loc.latitude, loc.longitude, radius, arrive, leave, text))
                        done(true)
                    }
                }
                .addOnFailureListener { done(false) }
        } catch (_: SecurityException) {
            done(false)
        }
    }

    fun describe(a: PlaceAlert): String {
        val when_ = when {
            a.onArrive && a.onLeave -> "בהגעה וביציאה"
            a.onArrive -> "בהגעה"
            a.onLeave -> "ביציאה"
            else -> "כבוי"
        }
        return "$when_ · ברדיוס ${a.radius.toInt()} מטר"
    }
}

/** הגעת למקום או יצאת ממנו */
class PlaceAlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val ids = event.triggeringGeofences?.map { it.requestId } ?: return
        val arrived = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
        val time = SimpleDateFormat("HH:mm", Locale("he")).format(Date())
        PlaceAlerts.all(ctx).filter { it.id in ids }.forEach { a ->
            if (arrived && !a.onArrive) return@forEach
            if (!arrived && !a.onLeave) return@forEach
            val title = if (arrived) "📍 הגעת ל${a.name}" else "🚶 יצאת מ${a.name}"
            Notify.post(
                ctx, (a.id + arrived).hashCode(),
                title,
                a.text.ifBlank { "בשעה $time" },
                Intent(ctx, HomeActivity::class.java),
            )
        }
    }
}

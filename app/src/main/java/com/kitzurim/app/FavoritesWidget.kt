package com.kitzurim.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/** וידג'ט המועדפים במסך הבית: עד 4 אנשים, לחיצה מחייגת מיד */
class FavoritesWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, views(ctx)) }
    }

    companion object {
        private val slots = listOf(
            Triple(R.id.fav1, R.id.fav_l1, R.id.fav_n1),
            Triple(R.id.fav2, R.id.fav_l2, R.id.fav_n2),
            Triple(R.id.fav3, R.id.fav_l3, R.id.fav_n3),
            Triple(R.id.fav4, R.id.fav_l4, R.id.fav_n4),
        )

        private fun views(ctx: Context): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_favorites)
            val favs = PhoneBook.favorites(ctx).take(4)
            v.setViewVisibility(R.id.fav_empty, if (favs.isEmpty()) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.fav_row, if (favs.isEmpty()) View.GONE else View.VISIBLE)
            slots.forEachIndexed { i, (box, letter, name) ->
                val f = favs.getOrNull(i)
                if (f == null) {
                    v.setViewVisibility(box, View.INVISIBLE)
                    return@forEachIndexed
                }
                v.setViewVisibility(box, View.VISIBLE)
                v.setTextViewText(letter, f.name.trim().take(1))
                v.setTextViewText(name, f.name.trim().substringBefore(" "))
                val call = PendingIntent.getActivity(
                    ctx, 100 + i,
                    Intent(ctx, DialActivity::class.java)
                        .putExtra(DialActivity.EXTRA_CALL, f.number)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                v.setOnClickPendingIntent(box, call)
            }
            // לחיצה על הכותרת פותחת את החייגן
            val open = PendingIntent.getActivity(
                ctx, 99, Intent(ctx, DialActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.fav_title, open)
            v.setOnClickPendingIntent(R.id.fav_empty, open)
            return v
        }

        /** מעדכן את כל הווידג'טים (למשל אחרי שסימנו מועדף חדש) */
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = runCatching { mgr.getAppWidgetIds(ComponentName(ctx, FavoritesWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) ids.forEach { mgr.updateAppWidget(it, views(ctx)) }
        }
    }
}

/**
 * הקיצור "טלפון" והווידג'ט של המועדפים: לא מותקנים מראש.
 * רק כשמפעילים את החייגן של קיצורים הם נדלקים, והטלפון שואל אם להוסיף אותם למסך הבית.
 */
object DialerHome {
    private const val SHORTCUT = "kitz_phone"

    private fun setComponents(ctx: Context, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        listOf(FavoritesWidget::class.java).forEach {
            runCatching {
                ctx.packageManager.setComponentEnabledSetting(ComponentName(ctx, it), state, PackageManager.DONT_KILL_APP)
            }
        }
    }

    /** מדליק ומבקש להוסיף למסך הבית: קודם הקיצור, ואחריו הווידג'ט */
    fun install(ctx: Context) {
        setComponents(ctx, true)
        val info = ShortcutInfoCompat.Builder(ctx, SHORTCUT)
            .setShortLabel("טלפון")
            .setLongLabel("טלפון של קיצורים")
            .setIcon(IconCompat.createWithResource(ctx, R.drawable.ic_phone_neon))
            .setIntent(Intent(ctx, DialActivity::class.java).setAction(Intent.ACTION_DIAL))
            .build()
        runCatching {
            ShortcutManagerCompat.enableShortcuts(ctx, listOf(info))
            if (ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
                ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
            }
        }
        // הווידג'ט: הטלפון שואל איפה לשים אותו (רגע אחרי, כדי שהשאלות לא יעלו אחת על השנייה)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching {
                val mgr = AppWidgetManager.getInstance(ctx)
                if (mgr.isRequestPinAppWidgetSupported) {
                    mgr.requestPinAppWidget(ComponentName(ctx, FavoritesWidget::class.java), null, null)
                }
            }
        }, 1500)
    }

    /** מכבה: הקיצור נהיה לא פעיל והווידג'ט נעלם מרשימת הווידג'טים */
    fun remove(ctx: Context) {
        runCatching { ShortcutManagerCompat.disableShortcuts(ctx, listOf(SHORTCUT), "החייגן של קיצורים כבוי") }
        setComponents(ctx, false)
    }
}

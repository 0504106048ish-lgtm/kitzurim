package com.kitzurim.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast

/** מה שכל כפתור בעיגול עושה בפועל */
object Actions {
    /**
     * מפעיל פעולה (מהמסך הראשי או מהעיגול).
     * מחזיר false כשחסר משהו בהגדרות (מספר טלפון או כתובת בית), ואז צריך לפתוח אותן.
     */
    fun run(ctx: Context, action: QuickAction): Boolean {
        when (action) {
            QuickAction.CHAT, QuickAction.MESSAGE -> {
                if (Prefs.phone(ctx).isBlank()) {
                    toast(ctx, "צריך להקליד מספר וואטסאפ בהגדרות")
                    return false
                }
                val text = if (action == QuickAction.MESSAGE) Prefs.message(ctx) else null
                openWhatsApp(ctx, Prefs.phone(ctx), text)
            }
            QuickAction.FLASH -> toggleTorch(ctx)
            QuickAction.NOTE -> ctx.startActivity(
                Intent(ctx, NoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            QuickAction.CAMERA -> openCamera(ctx)
            QuickAction.HOME -> {
                if (Prefs.home(ctx).isBlank()) {
                    toast(ctx, "צריך להקליד כתובת בית בהגדרות")
                    return false
                }
                navigateHome(ctx, Prefs.home(ctx))
            }
        }
        return true
    }

    /** 050-1234567 ← 972501234567 (הפורמט שוואטסאפ צריך) */
    fun normalizePhone(raw: String): String {
        val d = raw.filter { it.isDigit() }
        return if (d.startsWith("0")) "972" + d.drop(1) else d
    }

    /** פותח את הצ'אט בוואטסאפ. אם יש טקסט, הוא כבר כתוב בתיבה ונשאר רק ללחוץ "שלח" */
    fun openWhatsApp(ctx: Context, rawPhone: String, text: String?) {
        var url = "https://api.whatsapp.com/send?phone=" + normalizePhone(rawPhone)
        if (!text.isNullOrBlank()) url += "&text=" + Uri.encode(text)
        val base = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // קודם וואטסאפ רגיל, אחר כך וואטסאפ עסקי
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                ctx.startActivity(Intent(base).setPackage(pkg))
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        try {
            ctx.startActivity(base)
        } catch (_: ActivityNotFoundException) {
            toast(ctx, "וואטסאפ לא מותקן")
        }
    }

    fun toggleTorch(ctx: Context) {
        when (Torch.toggle(ctx)) {
            true -> toast(ctx, "הפנס נדלק")
            false -> toast(ctx, "הפנס כבה")
            null -> toast(ctx, "אי אפשר להדליק את הפנס כרגע")
        }
    }

    /** ניווט הביתה: קודם בוויז, ואם אין וויז אז בגוגל מפות */
    fun navigateHome(ctx: Context, address: String) {
        val waze = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://waze.com/ul?q=" + Uri.encode(address) + "&navigate=yes"),
        ).setPackage("com.waze").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(waze)
            return
        } catch (_: ActivityNotFoundException) {
        }
        val maps = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(address)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(maps)
        } catch (_: ActivityNotFoundException) {
            toast(ctx, "לא נמצאה אפליקציית ניווט")
        }
    }

    fun openCamera(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: ActivityNotFoundException) {
            toast(ctx, "לא נמצאה מצלמה")
        }
    }

    fun toast(ctx: Context, text: String) {
        Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_SHORT).show()
    }
}

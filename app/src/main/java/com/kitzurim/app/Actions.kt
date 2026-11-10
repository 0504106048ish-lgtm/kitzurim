package com.kitzurim.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast

/** מה שכל כפתור בעיגול עושה בפועל */
object Actions {
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

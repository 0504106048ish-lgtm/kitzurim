package com.kitzurim.app

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import java.util.Calendar

/**
 * אנדרואיד שולח לכאן רק שיחות ממספרים שלא שמורים באנשי הקשר.
 * בלילה (23:00 עד 07:00) השיחה נדחית בשקט ונרשמת ברשימה.
 */
class NightCallScreener : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val incoming = Build.VERSION.SDK_INT < 29 || details.callDirection == Call.Details.DIRECTION_INCOMING
        val reject = incoming && AutoPrefs.nightCallsEnabled(this) && NightCalls.isNight()
        val response = CallResponse.Builder()
        if (reject) {
            response.setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
                .setSkipCallLog(false)
            AutoPrefs.addRejected(this, details.handle?.schemeSpecificPart ?: "מספר חסוי")
        }
        respondToCall(details, response.build())
    }
}

object NightCalls {
    const val FROM_HOUR = 23
    const val TO_HOUR = 7

    fun isNight(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= FROM_HOUR || h < TO_HOUR
    }

    /** האם קיצורים קיבלה את התפקיד "סינון שיחות" */
    fun roleHeld(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val rm = ctx.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }
}

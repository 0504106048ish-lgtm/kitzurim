package com.kitzurim.app

import android.app.Activity
import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** השב מהגלולה: שורת כתיבה קטנה מעל המקלדת, ששולחת דרך הכפתור "השב" של ההתראה */
class QuickReplyActivity : Activity() {
    companion object {
        private var action: Notification.Action? = null
        private var to = ""

        fun open(c: Context, sender: String, a: Notification.Action) {
            action = a
            to = sender
            runCatching {
                c.startActivity(
                    Intent(c, QuickReplyActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a = action ?: return finish()
        val d = resources.displayMetrics.density
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(90, 0, 0, 0))
            setOnClickListener { finish() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 26 * d
                setColor(Color.BLACK)
            }
            isClickable = true
        }
        val input = EditText(this).apply {
            hint = "תשובה ל$to"
            setHintTextColor(Color.rgb(140, 146, 158))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            textDirection = View.TEXT_DIRECTION_ANY_RTL
        }
        val send = TextView(this).apply {
            text = "➤"
            rotation = 180f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(42, 212, 154))
            }
        }
        fun go() {
            val t = input.text.toString().trim()
            if (t.isEmpty()) return
            val inputs = a.remoteInputs ?: return finish()
            val results = Bundle()
            inputs.forEach { results.putCharSequence(it.resultKey, t) }
            val fill = Intent()
            RemoteInput.addResultsToIntent(inputs, fill, results)
            runCatching { a.actionIntent.send(this, 0, fill) }
            finish()
        }
        send.setOnClickListener { go() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { go(); true } else false }
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(send, LinearLayout.LayoutParams((38 * d).toInt(), (38 * d).toInt()))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins((10 * d).toInt(), 0, (10 * d).toInt(), (10 * d).toInt())
        })
        setContentView(root)
        input.requestFocus()
    }

    override fun onDestroy() {
        if (isFinishing) action = null
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}

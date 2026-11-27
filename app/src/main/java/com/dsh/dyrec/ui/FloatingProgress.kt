package com.dsh.dyrec.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮窗进度显示。需要 SYSTEM_ALERT_WINDOW 权限。
 * 只在主线程调用。
 */
class FloatingProgress(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: LinearLayout? = null
    private var label: TextView? = null

    fun show() {
        if (view != null) return
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 20, 30, 20)
            setBackgroundColor(0xCC101418.toInt())
        }
        val t = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            text = "准备录制…"
        }
        v.addView(t)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 160
        }
        try {
            wm.addView(v, lp)
            view = v
            label = t
        } catch (e: Exception) {
            // 没有悬浮窗权限，静默失败
        }
    }

    fun update(text: String) {
        label?.text = text
    }

    fun hide() {
        view?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        view = null
        label = null
    }
}

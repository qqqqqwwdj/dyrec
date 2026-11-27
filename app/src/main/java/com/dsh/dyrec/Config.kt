package com.dsh.dyrec

import android.content.Context

/** 应用配置读写 */
object Config {
    private const val PREF = "dyrec_cfg"
    private const val K_ENDPOINT = "resolver_endpoint"
    private const val K_AUTO_CLIP = "auto_clip"
    private const val K_FLOAT = "float_progress"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun resolverEndpoint(ctx: Context): String = p(ctx).getString(K_ENDPOINT, "").orEmpty()

    fun setResolverEndpoint(ctx: Context, v: String) {
        p(ctx).edit().putString(K_ENDPOINT, v.trim()).apply()
    }

    /** 是否开启自动捕获剪贴板 */
    fun autoClip(ctx: Context): Boolean = p(ctx).getBoolean(K_AUTO_CLIP, true)

    fun setAutoClip(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean(K_AUTO_CLIP, v).apply()
    }

    /** 是否显示悬浮窗进度 */
    fun floatProgress(ctx: Context): Boolean = p(ctx).getBoolean(K_FLOAT, true)

    fun setFloatProgress(ctx: Context, v: Boolean) {
        p(ctx).edit().putBoolean(K_FLOAT, v).apply()
    }
}

package com.dsh.dyrec.data

import android.content.Context
import org.json.JSONArray

/** 用 SharedPreferences 以 JSON 形式持久化任务列表，进程被杀也能恢复 */
object TaskStore {
    private const val PREF = "dyrec_tasks"
    private const val KEY = "tasks"

    fun load(ctx: Context): MutableList<RecordTask> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { RecordTask.fromJson(arr.getString(it)) }
        }.getOrDefault(mutableListOf())
    }

    fun save(ctx: Context, tasks: List<RecordTask>) {
        val arr = JSONArray()
        tasks.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }
}

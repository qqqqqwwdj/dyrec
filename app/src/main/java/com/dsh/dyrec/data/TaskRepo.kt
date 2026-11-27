package com.dsh.dyrec.data

import android.content.Context

/** 内存中的任务仓库，配合 TaskStore 持久化；UI 监听变化刷新 */
object TaskRepo {
    private val items = mutableListOf<RecordTask>()
    private val listeners = mutableSetOf<() -> Unit>()
    private var inited = false

    @Synchronized
    fun init(ctx: Context) {
        if (inited) return
        items.clear()
        items.addAll(TaskStore.load(ctx))
        inited = true
    }

    @Synchronized
    fun all(): List<RecordTask> = items.toList()

    @Synchronized
    fun find(id: String): RecordTask? = items.firstOrNull { it.id == id }

    @Synchronized
    fun add(ctx: Context, t: RecordTask) {
        items.add(0, t)
        persist(ctx)
        fire()
    }

    @Synchronized
    fun update(ctx: Context, t: RecordTask) {
        val i = items.indexOfFirst { it.id == t.id }
        if (i >= 0) items[i] = t else items.add(0, t)
        persist(ctx)
        fire()
    }

    @Synchronized
    fun remove(ctx: Context, id: String) {
        items.removeAll { it.id == id }
        persist(ctx)
        fire()
    }

    private fun persist(ctx: Context) = TaskStore.save(ctx, items)

    fun addListener(l: () -> Unit) { synchronized(this) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(this) { listeners.remove(l) } }

    private fun fire() {
        val snapshot = synchronized(this) { listeners.toList() }
        snapshot.forEach { runCatching { it() } }
    }
}

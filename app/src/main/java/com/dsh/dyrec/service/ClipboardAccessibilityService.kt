package com.dsh.dyrec.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipboardManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.dsh.dyrec.Config
import com.dsh.dyrec.data.RecordTask
import com.dsh.dyrec.data.TaskRepo
import com.dsh.dyrec.data.TaskState
import com.dsh.dyrec.resolve.DouyinLinkParser
import java.util.UUID

/**
 * 无障碍服务：Android 10+ 普通后台应用读不到剪贴板，
 * 这里借无障碍事件在剪贴板变化时读取，识别出抖音直播链接后生成一条待处理任务。
 */
class ClipboardAccessibilityService : AccessibilityService() {

    private var lastText: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!Config.autoClip(this)) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val clip = cm.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(this)?.toString() ?: return
        if (text == lastText) return
        lastText = text
        if (!DouyinLinkParser.isDouyin(text)) return
        val url = DouyinLinkParser.extractUrl(text) ?: return

        TaskRepo.init(this)
        // 同一链接不重复入列
        if (TaskRepo.all().any { it.sourceUrl == url && it.state == TaskState.PENDING }) return
        val task = RecordTask(
            id = UUID.randomUUID().toString(),
            sourceUrl = url,
            state = TaskState.PENDING
        )
        TaskRepo.add(this, task)
    }

    override fun onInterrupt() {}
}

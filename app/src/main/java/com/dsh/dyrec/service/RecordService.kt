package com.dsh.dyrec.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.format.Formatter
import com.dsh.dyrec.Config
import com.dsh.dyrec.data.RecordTask
import com.dsh.dyrec.data.TaskRepo
import com.dsh.dyrec.data.TaskState
import com.dsh.dyrec.record.RecordResult
import com.dsh.dyrec.record.StreamRecorder
import com.dsh.dyrec.ui.FloatingProgress
import com.dsh.dyrec.ui.Notifications
import java.io.File

/** 前台服务：承载录制线程、更新通知与悬浮窗，靠前台通知保活 */
class RecordService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var recorder: StreamRecorder? = null
    private var worker: Thread? = null
    private var floating: FloatingProgress? = null
    private var activeTaskId: String? = null
    @Volatile private var activeTitle: String = ""

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        TaskRepo.init(this)
        startForeground(Notifications.NOTIF_ID, Notifications.build(this, "直播录制", "空闲"))
        floating = FloatingProgress(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return START_STICKY
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "直播录制"
                val quality = intent.getStringExtra(EXTRA_QUALITY) ?: ""
                startRecord(id, url, title, quality)
            }
            ACTION_STOP -> stopActive()
        }
        return START_STICKY
    }

    private fun startRecord(id: String, url: String, title: String, quality: String) {
        if (worker?.isAlive == true) stopActive()
        TaskRepo.init(this)
        val task = TaskRepo.find(id) ?: RecordTask(id = id, sourceUrl = "")
        task.streamUrl = url
        task.quality = quality
        task.state = TaskState.RECORDING
        task.startedAt = System.currentTimeMillis()
        task.error = null
        TaskRepo.update(this, task)
        activeTaskId = id
        activeTitle = title

        val dir = File(getExternalFilesDir(null) ?: filesDir, "Records")
        if (!dir.exists()) dir.mkdirs()
        val safeName = sanitize("${title}_${quality}_${System.currentTimeMillis()}")
        val partFile = File(dir, "$safeName.part")

        recorder = StreamRecorder(url, partFile) { bytes -> onProgress(bytes) }
        if (Config.floatProgress(this)) main.post { floating?.show() }
        updateNotif("录制中：$title", "正在连接流…")

        val rec = recorder!!
        worker = Thread {
            val result = rec.run()
            finishTask(task, partFile, result)
        }.also { it.start() }
    }

    private fun onProgress(bytes: Long) {
        val mb = bytes / 1024.0 / 1024.0
        val text = String.format("已录 %.2f MB", mb)
        main.post {
            floating?.update("● 录制中\n$activeTitle\n$text")
            updateNotif("录制中：$activeTitle", text)
        }
    }

    private fun finishTask(task: RecordTask, partFile: File, result: RecordResult) {
        val stoppedByUser = task.state == TaskState.STOPPED
        val finalFile: File
        if (result.bytes > 0 && partFile.exists()) {
            val target = File(partFile.parentFile, partFile.nameWithoutExtension + "." + result.container.ext)
            if (target.exists()) target.delete()
            partFile.renameTo(target)
            if (partFile.exists()) partFile.delete()
            finalFile = target
        } else {
            partFile.delete()
            finalFile = partFile
        }

        task.bytesWritten = result.bytes
        task.stoppedAt = System.currentTimeMillis()
        task.durationSec = if (task.startedAt > 0) (task.stoppedAt - task.startedAt) / 1000 else 0
        task.filePath = if (result.bytes > 0) finalFile.absolutePath else ""
        task.state = when {
            result.bytes <= 0L -> TaskState.FAILED
            stoppedByUser -> TaskState.STOPPED
            else -> TaskState.DONE
        }
        task.error = result.error
        TaskRepo.update(this, task)

        main.post {
            floating?.hide()
            val msg = "${task.state} · ${Formatter.formatShortFileSize(this, task.bytesWritten)}"
            updateNotif("录制结束", msg)
        }
        activeTaskId = null
        recorder = null
    }

    private fun stopActive() {
        val rec = recorder ?: return
        activeTaskId?.let { id ->
            TaskRepo.find(id)?.let {
                it.state = TaskState.STOPPED
                TaskRepo.update(this, it)
            }
        }
        // 只置停止标志，录制线程退出时会截断并按边界收尾
        rec.stop()
        updateNotif("正在停止…", "等待截断并封装文件")
    }

    private fun updateNotif(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(Notifications.NOTIF_ID, Notifications.build(this, title, text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        recorder?.stop()
        floating?.hide()
        super.onDestroy()
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(60)

    companion object {
        const val ACTION_START = "com.dsh.dyrec.START"
        const val ACTION_STOP = "com.dsh.dyrec.STOP"
        const val EXTRA_ID = "id"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_QUALITY = "quality"

        fun start(ctx: Context, taskId: String, url: String, title: String, quality: String) {
            val i = Intent(ctx, RecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_ID, taskId)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_QUALITY, quality)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, RecordService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }
    }
}

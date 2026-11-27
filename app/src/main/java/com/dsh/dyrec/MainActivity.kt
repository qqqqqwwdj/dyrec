package com.dsh.dyrec

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dsh.dyrec.data.RecordTask
import com.dsh.dyrec.data.TaskRepo
import com.dsh.dyrec.data.TaskState
import com.dsh.dyrec.resolve.DouyinLinkParser
import com.dsh.dyrec.resolve.HttpResolver
import com.dsh.dyrec.service.ClipboardAccessibilityService
import com.dsh.dyrec.service.RecordService
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvResolve: TextView
    private lateinit var etLink: EditText
    private lateinit var etEndpoint: EditText
    private val adapter = TaskAdapter()
    private val io = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        TaskRepo.init(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvResolve = findViewById(R.id.tvResolve)
        etLink = findViewById(R.id.etLink)
        etEndpoint = findViewById(R.id.etEndpoint)
        val rv = findViewById<RecyclerView>(R.id.rvTasks)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        etEndpoint.setText(Config.resolverEndpoint(this))

        findViewById<Button>(R.id.btnNotif).setOnClickListener { askNotif() }
        findViewById<Button>(R.id.btnOverlay).setOnClickListener { askOverlay() }
        findViewById<Button>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { askBattery() }
        findViewById<Button>(R.id.btnResolve).setOnClickListener { onResolve() }
        findViewById<Button>(R.id.btnSaveEndpoint).setOnClickListener {
            Config.setResolverEndpoint(this, etEndpoint.text.toString())
            toast("已保存")
        }

        TaskRepo.addListener { runOnUiThread { refresh() } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun refresh() {
        adapter.submit(TaskRepo.all())
        tvStatus.text = "无障碍：${if (isAccessibilityOn()) "已开启" else "未开启"}    悬浮窗：${if (canOverlay()) "已授权" else "未授权"}"
    }

    // ---------- 权限 ----------

    private fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            toast("当前系统无需单独申请")
        }
    }

    private fun askOverlay() {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        startActivity(i)
    }

    private fun askBattery() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("已忽略电池优化")
                return
            }
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(i)
        } catch (e: Exception) {
            toast("请到系统设置里手动忽略电池优化")
        }
    }

    private fun isAccessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName, ignoreCase = true) &&
                enabled.contains(ClipboardAccessibilityService::class.java.simpleName, ignoreCase = true)
    }

    private fun canOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    // ---------- 解析 / 录制 ----------

    private fun onResolve() {
        val raw = etLink.text.toString().trim()
        if (raw.isEmpty()) { toast("请先粘贴链接"); return }

        if (isDirectStream(raw)) {
            startRecord(raw, "直链录制", "直链")
            return
        }
        if (!DouyinLinkParser.isDouyin(raw)) {
            tvResolve.text = "既不是抖音链接，也不是 .flv/.ts/.m3u8 直链"
            return
        }
        val endpoint = Config.resolverEndpoint(this)
        if (endpoint.isBlank()) {
            tvResolve.text = "未配置解析服务地址。请到底部填写（阶段二），或直接粘贴 .flv/.ts 直链录制。"
            return
        }
        tvResolve.text = "解析中…"
        io.execute {
            val parsed = runCatching { DouyinLinkParser.parse(raw) }
                .getOrDefault(DouyinLinkParser.ParsedLink(null, null, raw, raw))
            val result = HttpResolver(endpoint).resolve(parsed.finalUrl)
            val best = result.pickBest()
            runOnUiThread {
                if (best == null) {
                    tvResolve.text = "解析失败：${result.note ?: "无可用直链"}"
                } else {
                    tvResolve.text = "可选清晰度：\n" +
                            result.streams.joinToString("\n") { "· ${it.quality}" } +
                            "\n选用：${best.quality}"
                    startRecord(best.url, result.title ?: "抖音直播", best.quality)
                }
            }
        }
    }

    private fun startRecord(url: String, title: String, quality: String) {
        val task = RecordTask(id = UUID.randomUUID().toString(), sourceUrl = url, quality = quality)
        TaskRepo.add(this, task)
        RecordService.start(this, task.id, url, title, quality)
        toast("已开始录制")
    }

    private fun isDirectStream(u: String): Boolean {
        val path = u.substringBefore('?').lowercase()
        return path.endsWith(".flv") || path.endsWith(".ts") || path.endsWith(".m3u8")
    }

    private fun openFile(path: String) {
        if (path.isBlank()) { toast("文件路径为空"); return }
        val f = File(path)
        if (!f.exists()) { toast("文件不存在"); return }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(i)
        } catch (e: Exception) {
            toast("没有可播放该视频的应用")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- 列表 ----------

    private inner class TaskAdapter : RecyclerView.Adapter<TaskAdapter.VH>() {
        private var data: List<RecordTask> = emptyList()

        fun submit(list: List<RecordTask>) {
            data = list
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvInfo: TextView = v.findViewById(R.id.tvInfo)
            val btnAction: Button = v.findViewById(R.id.btnAction)
            val btnDel: Button = v.findViewById(R.id.btnDel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_task, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = data[position]
            val size = if (t.bytesWritten > 0) " · ${t.bytesWritten / 1024 / 1024} MB" else ""
            val name = t.filePath.substringAfterLast('/').ifBlank { t.sourceUrl.take(40) }
            holder.tvInfo.text = "${t.quality} · ${t.state}$size\n$name"
            when (t.state) {
                TaskState.RECORDING -> {
                    holder.btnAction.text = "停止"
                    holder.btnAction.setOnClickListener { RecordService.stop(this@MainActivity) }
                }
                TaskState.DONE, TaskState.STOPPED -> {
                    holder.btnAction.text = "打开"
                    holder.btnAction.setOnClickListener { openFile(t.filePath) }
                }
                TaskState.PENDING -> {
                    holder.btnAction.text = "录制"
                    holder.btnAction.setOnClickListener {
                        etLink.setText(t.sourceUrl)
                        onResolve()
                    }
                }
                else -> {
                    holder.btnAction.text = "—"
                    holder.btnAction.setOnClickListener(null)
                }
            }
            holder.btnDel.setOnClickListener { TaskRepo.remove(this@MainActivity, t.id) }
        }
    }
}

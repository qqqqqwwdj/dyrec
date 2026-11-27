package com.dsh.dyrec.record

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.util.concurrent.TimeUnit

enum class Container(val label: String, val ext: String) {
    FLV("flv", "flv"),
    TS("ts", "ts"),
    HLS("hls", "ts"),
    UNKNOWN("bin", "bin")
}

data class RecordResult(
    val file: File,
    val bytes: Long,
    val playable: Boolean,
    val container: Container,
    val error: String? = null
)

/**
 * 容器边界跟踪器。
 * 它按 FLV tag / TS 188 字节包 的边界推进 goodOffset，
 * 停止录制时只要把文件截断到 goodOffset，产物就是一个结构完整、可正常打开的视频，
 * 而不是半截损坏文件。这是需求 4 的关键。
 */
class BoundaryTracker(val container: Container) {
    var goodOffset: Long = 0L
        private set

    private var pos = 0L
    private var state = 0          // FLV: 0=header(13B) 1=tagHeader(11B) 2=tagBody
    private var fill = 0
    private var need = 13
    private val hdr = ByteArray(13)
    private var bodyRemaining = 0L

    fun consume(d: ByteArray, off: Int, len: Int) {
        when (container) {
            Container.TS, Container.HLS -> {
                pos += len
                goodOffset = pos - (pos % 188L)
                return
            }
            Container.UNKNOWN -> {
                pos += len
                goodOffset = pos
                return
            }
            Container.FLV -> Unit
        }
        var i = off
        val end = off + len
        while (i < end) {
            when (state) {
                0 -> {
                    val take = minOf(need - fill, end - i)
                    System.arraycopy(d, i, hdr, fill, take)
                    fill += take; i += take; pos += take
                    if (fill == need) {
                        goodOffset = pos; fill = 0; need = 11; state = 1
                    }
                }
                1 -> {
                    val take = minOf(need - fill, end - i)
                    System.arraycopy(d, i, hdr, fill, take)
                    fill += take; i += take; pos += take
                    if (fill == need) {
                        // tag header: Type(1) DataSize(3) ...
                        val dataSize = ((hdr[1].toInt() and 0xFF) shl 16) or
                                ((hdr[2].toInt() and 0xFF) shl 8) or
                                (hdr[3].toInt() and 0xFF)
                        bodyRemaining = dataSize.toLong() + 4L   // data + previousTagSize
                        fill = 0; state = 2
                    }
                }
                else -> {
                    val take = minOf(bodyRemaining, (end - i).toLong()).toInt()
                    i += take; pos += take; bodyRemaining -= take
                    if (bodyRemaining == 0L) {
                        goodOffset = pos; fill = 0; need = 11; state = 1
                    }
                }
            }
        }
    }
}

/**
 * 流录制器，支持三种输入：
 *  - 直接 flv 直链
 *  - 直接 ts 直链
 *  - m3u8（HLS，直播切片；自动选最高码率变体，循环拉新切片）
 *
 * 边下边写，停止时按容器边界截断。
 */
class StreamRecorder(
    private val url: String,
    private val outFile: File,
    private val onProgress: (Long) -> Unit
) {
    @Volatile private var stop = false
    @Volatile var container: Container = Container.UNKNOWN
        private set

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun stop() { stop = true }

    fun run(): RecordResult = try {
        if (url.contains(".m3u8", true)) runHls() else runDirect()
    } catch (e: Exception) {
        RecordResult(outFile, if (outFile.exists()) outFile.length() else 0L, false, container, e.message ?: e.toString())
    }

    // ---------- 直连流 ----------

    private fun runDirect(): RecordResult {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://live.douyin.com/")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                return RecordResult(outFile, 0L, false, container, "HTTP ${resp.code}")
            }
            val body = resp.body ?: return RecordResult(outFile, 0L, false, container, "空响应")
            val raf = RandomAccessFile(outFile, "rw")
            raf.setLength(0)
            val ins = body.byteStream()
            val buf = ByteArray(64 * 1024)
            var first = ins.read(buf)
            if (first <= 0) {
                raf.close()
                return RecordResult(outFile, 0L, false, container, "无数据")
            }
            container = detect(buf, first)
            val tracker = BoundaryTracker(container)
            var total = 0L
            raf.write(buf, 0, first); tracker.consume(buf, 0, first); total += first
            onProgress(total)
            while (!stop) {
                val n = ins.read(buf)
                if (n < 0) break
                raf.write(buf, 0, n); tracker.consume(buf, 0, n); total += n
                onProgress(total)
            }
            val good = tracker.goodOffset
            if (good > 0 && good < raf.length()) raf.setLength(good)
            val len = raf.length()
            raf.close()
            return RecordResult(outFile, len, good > 0, container)
        }
    }

    // ---------- HLS ----------

    private fun runHls(): RecordResult {
        container = Container.HLS
        val raf = RandomAccessFile(outFile, "rw")
        raf.setLength(0)
        var total = 0L
        val tracker = BoundaryTracker(Container.TS)
        val seen = HashSet<String>()
        var playlistUrl = url
        var idleRounds = 0

        while (!stop) {
            var text = fetchText(playlistUrl)
            if (text == null) {
                if (seen.isEmpty()) return RecordResult(outFile, 0L, false, container, "无法获取 m3u8")
                break
            }
            // 主播放列表：选最高码率变体
            selectVariantIfMaster(text, playlistUrl)?.let { v ->
                playlistUrl = v
                text = fetchText(v) ?: text
            }
            val isLive = !text.contains("#EXT-X-ENDLIST")
            val segs = parseSegments(text, playlistUrl, seen)
            if (segs.isEmpty()) idleRounds++ else idleRounds = 0

            for (seg in segs) {
                if (stop) break
                downloadInto(seg, raf, tracker) { total += it; onProgress(total) }
            }

            if (!isLive) break
            if (stop) break
            if (idleRounds >= 6) break
            try { Thread.sleep(if (segs.isEmpty()) 3000L else 1200L) } catch (e: InterruptedException) { break }
        }

        val good = tracker.goodOffset
        if (good > 0 && good < raf.length()) raf.setLength(good)
        val len = raf.length()
        raf.close()
        return RecordResult(outFile, len, good > 0, container)
    }

    private fun downloadInto(
        segUrl: String,
        raf: RandomAccessFile,
        tracker: BoundaryTracker,
        onChunk: (Long) -> Unit
    ) {
        try {
            val req = Request.Builder().url(segUrl)
                .header("User-Agent", UA)
                .header("Referer", "https://live.douyin.com/")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return
                val ins = resp.body?.byteStream() ?: return
                val buf = ByteArray(64 * 1024)
                while (!stop) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n); tracker.consume(buf, 0, n); onChunk(n.toLong())
                }
                ins.close()
            }
        } catch (e: Exception) {
            // 单个切片失败不影响整体，继续下一个
        }
    }

    private fun parseSegments(m3u8: String, baseUrl: String, seen: MutableSet<String>): List<String> {
        val out = ArrayList<String>()
        val base = URI(baseUrl)
        for (line in m3u8.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val abs = try { base.resolve(t).toString() } catch (e: Exception) { t }
            if (seen.add(abs)) out.add(abs)
        }
        return out
    }

    private fun selectVariantIfMaster(text: String, baseUrl: String): String? {
        if (!text.contains("#EXT-X-STREAM-INF")) return null
        var best: String? = null
        var bestBw = -1L
        val lines = text.lines()
        for (i in lines.indices) {
            if (!lines[i].trim().startsWith("#EXT-X-STREAM-INF")) continue
            val bw = Regex("BANDWIDTH=(\\d+)").find(lines[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            var j = i + 1
            while (j < lines.size && (lines[j].trim().startsWith("#") || lines[j].isBlank())) j++
            if (j < lines.size && bw > bestBw) {
                bestBw = bw
                best = try { URI(baseUrl).resolve(lines[j].trim()).toString() } catch (e: Exception) { lines[j].trim() }
            }
        }
        return best
    }

    private fun fetchText(u: String): String? = try {
        val req = Request.Builder().url(u)
            .header("User-Agent", UA)
            .header("Referer", "https://live.douyin.com/")
            .build()
        client.newCall(req).execute().use { if (it.isSuccessful) it.body?.string() else null }
    } catch (e: Exception) { null }

    private fun detect(b: ByteArray, n: Int): Container {
        if (n >= 3 && b[0] == 'F'.code.toByte() && b[1] == 'L'.code.toByte() && b[2] == 'V'.code.toByte()) {
            return Container.FLV
        }
        if (n > 0 && (b[0].toInt() and 0xFF) == 0x47) {
            if (n < 189 || (b[188].toInt() and 0xFF) == 0x47) return Container.TS
        }
        return Container.UNKNOWN
    }

    private companion object {
        const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}

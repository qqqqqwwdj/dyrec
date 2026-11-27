package com.dsh.dyrec.resolve

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * 只做「分享文案 -> 链接 -> room_id / web_rid」这一层，属于公开信息解析，不涉及签名。
 * 真正换出播放直链由 [HttpResolver] 交给解析服务完成。
 */
object DouyinLinkParser {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val shortLink = Pattern.compile("https?://v\\.douyin\\.com/[0-9A-Za-z]+/?")
    private val anyDouyin = Pattern.compile("https?://[^\\s，,。；;]*douyin\\.com[^\\s，,。；;]*")
    private val liveHost = Pattern.compile("live\\.douyin\\.com/(\\d+)")
    private val roomIdQ = Pattern.compile("[?&]room_id=(\\d+)")
    private val webRidQ = Pattern.compile("[?&]web_rid=([0-9A-Za-z_\\-]+)")

    data class ParsedLink(
        val roomId: String?,
        val webRid: String?,
        val finalUrl: String,
        val raw: String
    ) {
        val idForService: String? get() = roomId ?: webRid
    }

    fun isDouyin(text: String): Boolean =
        text.contains("douyin.com") || text.contains("iesdouyin.com")

    /** 从一段分享文案里抠出可用的 URL */
    fun extractUrl(text: String): String? {
        shortLink.matcher(text).let { if (it.find()) return it.group() }
        anyDouyin.matcher(text).let { if (it.find()) return it.group() }
        return null
    }

    /** 跟随短链跳转，拿到最终地址（会带上 room_id 等参数） */
    fun expand(url: String): String = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { it.request.url.toString() }
    } catch (e: Exception) {
        url
    }

    fun parse(text: String): ParsedLink {
        val link = extractUrl(text) ?: text
        val finalUrl = expand(link)
        var roomId: String? = null
        var webRid: String? = null
        liveHost.matcher(finalUrl).let { if (it.find()) webRid = it.group(1) }
        roomIdQ.matcher(finalUrl).let { if (it.find()) roomId = it.group(1) }
        webRidQ.matcher(finalUrl).let { if (it.find() && webRid == null) webRid = it.group(1) }
        return ParsedLink(roomId, webRid, finalUrl, text)
    }
}

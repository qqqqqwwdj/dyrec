package com.dsh.dyrec.resolve

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 解析服务客户端。你只需部署一个符合下述契约的 HTTP 服务，把它地址填进设置即可。
 *
 * 请求：
 *   GET {endpoint}?url={URL编码的分享链接}
 *   或 endpoint 里写 {url} 占位符，例如 https://your.host/resolve?u={url}
 *
 * 期望返回 JSON（下面几种都兼容）：
 *   {"roomId":"...","title":"...","streams":[{"quality":"原画","url":"https://...flv"}, ...]}
 *   {"data":{"streams":[...]}}   或   [{"quality":"原画","url":"..."}, ...]
 */
class HttpResolver(private val endpoint: String) : LiveResolver {

    override fun canHandle(url: String): Boolean = endpoint.isNotBlank()

    override fun resolve(shareUrl: String): ResolveResult {
        if (endpoint.isBlank()) {
            return ResolveResult(null, null, emptyList(), "未配置解析服务地址，请到设置里填写")
        }
        val full = buildUrl(shareUrl)
        return try {
            val req = Request.Builder().url(full)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return ResolveResult(null, null, emptyList(), "解析服务 HTTP ${resp.code}")
                }
                parse(body)
            }
        } catch (e: Exception) {
            ResolveResult(null, null, emptyList(), "解析服务请求失败：${e.message}")
        }
    }

    private fun buildUrl(shareUrl: String): String {
        val enc = URLEncoder.encode(shareUrl, "UTF-8")
        return if (endpoint.contains("{url}")) {
            endpoint.replace("{url}", enc)
        } else {
            endpoint + (if (endpoint.contains("?")) "&" else "?") + "url=" + enc
        }
    }

    private fun parse(body: String): ResolveResult {
        val root = runCatching { JSONObject(body) }.getOrNull()
        // 纯数组形式
        if (root == null) {
            val arr = runCatching { JSONArray(body) }.getOrNull()
                ?: return ResolveResult(null, null, emptyList(), "返回内容不是合法 JSON")
            return ResolveResult(null, null, readStreams(arr), null)
        }
        // 兼容 data 包裹
        val holder = root.optJSONObject("data") ?: root
        val arr = holder.optJSONArray("streams")
            ?: holder.optJSONArray("urls")
            ?: holder.optJSONArray("qualities")
            ?: JSONArray()
        val roomId = holder.optString("roomId").ifEmpty {
            holder.optString("room_id").ifEmpty { null }
        }
        val title = holder.optString("title").ifEmpty { null }
        val note = holder.optString("note").ifEmpty { null }
        val list = readStreams(arr)
        return ResolveResult(
            roomId,
            title,
            list,
            note ?: if (list.isEmpty()) "解析服务未返回任何直链" else null
        )
    }

    private fun readStreams(arr: JSONArray): List<StreamOption> {
        val out = ArrayList<StreamOption>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").ifEmpty {
                o.optString("addr").ifEmpty {
                    o.optString("play_url").ifEmpty { o.optString("stream_url") }
                }
            }
            if (url.isBlank()) continue
            val quality = o.optString("quality").ifEmpty {
                o.optString("name").ifEmpty { o.optString("title") }
            }
            val container = o.optString("container")
            out.add(StreamOption(quality.ifBlank { "未知" }, url, container))
        }
        return out
    }

    private companion object {
        const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

package com.dsh.dyrec.resolve

/** 一个可选清晰度的流 */
data class StreamOption(
    val quality: String,   // 原画 / 蓝光 / 高清 / 标清 ...
    val url: String,       // flv / ts / m3u8 直链
    val container: String = "" // flv / ts / hls，留空则自动判断
)

/** 解析结果 */
data class ResolveResult(
    val roomId: String?,
    val title: String?,
    val streams: List<StreamOption>,
    val note: String? = null
) {
    /**
     * 按优先级挑流：原画 > 蓝光 > 高清 > 超清 > 标清 > 第一个
     */
    fun pickBest(): StreamOption? {
        if (streams.isEmpty()) return null
        val order = listOf("原画", "蓝光", "蓝光4M", "超清", "高清", "标清", "流畅")
        for (key in order) {
            streams.firstOrNull { it.quality.contains(key) }?.let { return it }
        }
        return streams.first()
    }
}

/** 解析器接口：以后接第三方服务或自建签名服务，都实现这个 */
interface LiveResolver {
    /** 能否处理这个链接 */
    fun canHandle(url: String): Boolean

    /**
     * 解析链接，返回多档直链。
     * 不抛异常地返回失败更适合 UI：失败时返回空 streams 并在 note 说明。
     */
    fun resolve(shareUrl: String): ResolveResult
}

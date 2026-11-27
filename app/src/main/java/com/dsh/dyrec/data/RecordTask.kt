package com.dsh.dyrec.data

import org.json.JSONObject

enum class TaskState { PENDING, RESOLVING, RECORDING, STOPPED, DONE, FAILED }

/** 一条录制任务 */
data class RecordTask(
    val id: String,
    val sourceUrl: String,
    var roomId: String? = null,
    var quality: String = "",
    var streamUrl: String = "",
    var filePath: String = "",
    var state: TaskState = TaskState.PENDING,
    var bytesWritten: Long = 0L,
    var startedAt: Long = 0L,
    var stoppedAt: Long = 0L,
    var error: String? = null,
    var durationSec: Long = 0L
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("sourceUrl", sourceUrl)
        put("roomId", roomId ?: "")
        put("quality", quality)
        put("streamUrl", streamUrl)
        put("filePath", filePath)
        put("state", state.name)
        put("bytesWritten", bytesWritten)
        put("startedAt", startedAt)
        put("stoppedAt", stoppedAt)
        put("error", error ?: "")
        put("durationSec", durationSec)
    }.toString()

    companion object {
        fun fromJson(s: String): RecordTask {
            val o = JSONObject(s)
            return RecordTask(
                id = o.getString("id"),
                sourceUrl = o.optString("sourceUrl"),
                roomId = o.optString("roomId").ifEmpty { null },
                quality = o.optString("quality"),
                streamUrl = o.optString("streamUrl"),
                filePath = o.optString("filePath"),
                state = runCatching { TaskState.valueOf(o.getString("state")) }
                    .getOrDefault(TaskState.PENDING),
                bytesWritten = o.optLong("bytesWritten"),
                startedAt = o.optLong("startedAt"),
                stoppedAt = o.optLong("stoppedAt"),
                error = o.optString("error").ifEmpty { null },
                durationSec = o.optLong("durationSec")
            )
        }
    }
}

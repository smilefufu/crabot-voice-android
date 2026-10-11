package com.crabot.voice

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Frozen research thresholds, not a claim that household calibration has passed. */
object Identity {
    const val SCORE = 0.50f
    const val MARGIN = 0.08f
    const val MIN_CLUSTER_SAMPLES = 24_000
    data class Segment(val start: Int, val end: Int, val speaker: Int)
    fun ranges(raw: FloatArray, count: Int): List<Segment> {
        require(raw.size % 3 == 0 && raw.size in 3..96) { "没有有效分段或分段过多，请重说" }
        return raw.toList().chunked(3).map {
            require(it.all(Float::isFinite)) { "无效分段" }
            val start = (it[0] * 16000).roundToInt()
            val end = (it[1] * 16000).roundToInt()
            require(start >= 0 && end <= count && end > start && it[2] >= 0 && it[2] == it[2].roundToInt().toFloat()) { "无效分段" }
            Segment(start, end, it[2].roundToInt())
        }
    }
    fun requireComplete(ranges: List<Segment>) {
        require(ranges.zipWithNext().all { (a, b) -> b.start >= a.end }) { "分段重叠或无序，内容无法归属；整轮未提交，请轮流重说" }
    }
    fun report(pcm: ShortArray, models: NativeModels, stored: JSONObject, registrations: JSONArray): JSONArray {
        val ranges = ranges(models.segments(pcm), pcm.size)
        requireComplete(ranges)
        val candidates = (0 until registrations.length()).map { registrations.getJSONObject(it) }
            .filter { it.getString("status") == "active" && it.getString("model_id") == Models.EMBEDDING && stored.has(it.getString("registration_id")) }
        val vectors = candidates.map { r ->
            val obj = stored.getJSONObject(r.getString("registration_id"))
            require(obj.getString("model_id") == Models.EMBEDDING) { "登记模型已变化，需重新登记" }
            val array = obj.getJSONArray("values"); FloatArray(array.length()) { array.getDouble(it).toFloat() }
        }
        try {
            val identities = ranges.groupBy { it.speaker }.mapValues { (_, segments) ->
                val audio = ShortArray(segments.sumOf { it.end - it.start })
                var offset = 0
                segments.forEach { pcm.copyInto(audio, offset, it.start, it.end); offset += it.end - it.start }
                val result = JSONObject().put("identity_status", if (candidates.isEmpty()) "unknown" else "uncertain")
                try {
                    if (candidates.isNotEmpty() && audio.size >= MIN_CLUSTER_SAMPLES) {
                        val v = models.embedding(audio)
                        try {
                            if (v.isNotEmpty()) {
                                val hits = models.matches(v, vectors)
                                val score = hits.getOrNull(1) ?: -1f
                                val margin = score - (hits.getOrNull(3) ?: -1f)
                                result.put("score", score.toDouble()).put("margin", margin.toDouble())
                                when {
                                    score < SCORE -> result.put("identity_status", "unknown")
                                    margin < MARGIN -> result.put("identity_status", "uncertain")
                                    else -> result.put("identity_status", "matched").put("registration_id", candidates[hits[0].toInt()].getString("registration_id"))
                                }
                            }
                        } finally { v.fill(0f) }
                    }
                    result
                } finally { audio.fill(0) }
            }
            return JSONArray(ranges.mapIndexed { i, segment ->
                JSONObject(identities.getValue(segment.speaker).toString()).put("segment_index", i)
                    .put("start_sample", segment.start).put("end_sample", segment.end).put("anonymous_speaker", "speaker-${segment.speaker}")
            })
        } finally { vectors.forEach { it.fill(0f) } }
    }
}

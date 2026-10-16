package com.crabot.voice

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

interface SpeakerIdentityModel {
    fun dimension(): Int
    fun segments(samples: ShortArray): FloatArray
    fun embedding(samples: ShortArray): FloatArray
    fun matches(vector: FloatArray, candidates: List<FloatArray>): FloatArray
}

/** Frozen research thresholds, not a claim that household calibration has passed. */
object Identity {
    const val SCORE = 0.50f
    const val MARGIN = 0.08f
    const val MIN_ENROLLMENT_SAMPLES = 24_000
    data class Segment(val start: Int, val end: Int, val speaker: Int)
    data class Diagnostic(val speaker: Int, val samples: Int, val candidates: Int, val compared: Boolean,
                          val score: Float?, val margin: Float?, val reason: String?)
    private data class Recognition(val status: String = "uncertain", val reason: String?, val index: Int? = null,
                                   val compared: Boolean = false, val score: Float? = null, val margin: Float? = null)
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
    fun enrollmentSample(audio: ShortArray, models: SpeakerIdentityModel): FloatArray {
        require(audio.size in 48_000..80_000) { "每段登记需 3–5 秒，请重新录这一段" }
        val segments = ranges(models.segments(audio), audio.size)
        requireComplete(segments)
        require(segments.map { it.speaker }.distinct().size == 1 && segments.sumOf { it.end - it.start } >= MIN_ENROLLMENT_SAMPLES) {
            "登记需一人清楚说话，请重新录这一段"
        }
        val vector = models.embedding(audio)
        if (!validVector(vector, models.dimension())) {
            vector.fill(0f)
            throw IllegalArgumentException("登记声纹特征无效，请重新录这一段")
        }
        return vector
    }
    fun enrollmentCandidate(samples: List<FloatArray>, models: SpeakerIdentityModel): FloatArray {
        val dimension = models.dimension()
        require(samples.size == 3 && samples.all { validVector(it, dimension) }) { "需要三段有效的登记声纹" }
        for (i in samples.indices) for (j in i + 1 until samples.size) {
            val hits = models.matches(samples[i], listOf(samples[j]))
            require(validMatches(hits, 1)) { "登记声纹比对结果无效，请重新录入" }
            require(hits[1] >= SCORE) { "三段声音不一致，请由同一人重新录入" }
        }
        val mean = FloatArray(dimension) { i -> (samples.sumOf { it[i].toDouble() } / 3).toFloat() }
        if (!validVector(mean, dimension)) {
            mean.fill(0f)
            throw IllegalArgumentException("合成登记声纹无效，请重新录入")
        }
        return mean
    }
    fun report(pcm: ShortArray, models: SpeakerIdentityModel, stored: JSONObject, registrations: JSONArray,
               diagnostic: (Diagnostic) -> Unit = {}): JSONArray {
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
            val identities = ranges.groupBy { it.speaker }.mapValues { (speaker, segments) ->
                val audio = ShortArray(segments.sumOf { it.end - it.start })
                var offset = 0
                segments.forEach { pcm.copyInto(audio, offset, it.start, it.end); offset += it.end - it.start }
                try {
                    val match = recognize(audio, models, vectors)
                    val result = JSONObject().put("identity_status", match.status)
                    match.score?.let { result.put("score", it.toDouble()) }
                    match.margin?.let { result.put("margin", it.toDouble()) }
                    match.index?.let { result.put("registration_id", candidates[it].getString("registration_id")) }
                    diagnostic(Diagnostic(speaker, audio.size, candidates.size, match.compared, match.score, match.margin, match.reason))
                    result
                } finally { audio.fill(0) }
            }
            return JSONArray(ranges.mapIndexed { i, segment ->
                JSONObject(identities.getValue(segment.speaker).toString()).put("segment_index", i)
                    .put("start_sample", segment.start).put("end_sample", segment.end).put("anonymous_speaker", "speaker-${segment.speaker}")
            })
        } finally { vectors.forEach { it.fill(0f) } }
    }
    private fun recognize(audio: ShortArray, models: SpeakerIdentityModel, vectors: List<FloatArray>): Recognition {
        if (vectors.isEmpty()) return Recognition("unknown", "尚无可用的登记声纹")
        val vector = models.embedding(audio)
        try {
            val dimension = models.dimension()
            if (vector.isEmpty()) return Recognition(reason = "本段语音暂时无法提取声纹")
            if (!validVector(vector, dimension)) return Recognition(reason = "本段声纹特征无效")
            if (vectors.any { !validVector(it, dimension) }) return Recognition(reason = "登记声纹特征无效")
            val hits = models.matches(vector, vectors)
            if (!validMatches(hits, vectors.size)) return Recognition(reason = "声纹比对结果无效", compared = true)
            val score = hits[1]; val margin = score - (hits.getOrNull(3) ?: -1f)
            val scored = Recognition(reason = null, compared = true, score = score, margin = margin)
            return when {
                score < SCORE -> scored.copy(status = "unknown", reason = "与已登记声纹不匹配")
                margin < MARGIN -> scored.copy(reason = "多个登记声纹相近，暂无法确认")
                else -> scored.copy(status = "matched", index = hits[0].toInt())
            }
        } finally { vector.fill(0f) }
    }
    private fun validVector(vector: FloatArray, dimension: Int): Boolean = dimension > 0 && vector.size == dimension
        && vector.all { it.isFinite() } && vector.sumOf { it.toDouble() * it } > 0
    private fun validMatches(hits: FloatArray, candidates: Int): Boolean {
        if (hits.size != minOf(2, candidates) * 2) return false
        for (i in hits.indices step 2) {
            val index = hits[i]; val score = hits[i + 1]
            if (!index.isFinite() || index != index.roundToInt().toFloat() || index < 0 || index >= candidates
                || !score.isFinite() || score !in -1.00001f..1.00001f) return false
        }
        return hits.size == 2 || (hits[0] != hits[2] && hits[1] >= hits[3])
    }
}

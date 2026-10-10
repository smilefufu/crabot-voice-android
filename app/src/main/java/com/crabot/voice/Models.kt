package com.crabot.voice

import android.content.Context
import android.icu.text.Transliterator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object Models {
    const val SEGMENTATION = "pyannote-segmentation-3.0-fp32@220ad67ca923bef2fa91f2390c786097bf305bceb5e261d4af67b38e938e1079"
    const val EMBEDDING = "campplus-zh@f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11"
    fun versions() = JSONObject().put("segmentation", SEGMENTATION).put("embedding", EMBEDDING)
    fun prepare(context: Context): String {
        val manifest = JSONObject(context.assets.open("model-files.json").bufferedReader().use { it.readText() })
        val root = File(context.noBackupFilesDir, "models-v1").apply { mkdirs() }
        manifest.keys().forEach { name ->
            val expected = manifest.getString(name)
            val target = File(root, name)
            fun digest(file: File): String = file.inputStream().use { input ->
                val sha = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) }
                sha.digest().joinToString("") { "%02x".format(it) }
            }
            if (!target.exists() || digest(target) != expected) {
                val temp = File(root, "$name.tmp")
                context.assets.open("models/$name").use { src -> temp.outputStream().use { src.copyTo(it) } }
                require(digest(temp) == expected) { "模型校验失败" }
                require(temp.renameTo(target)) { "无法保存模型" }
            }
        }
        return root.absolutePath
    }
    fun keyword(word: String, tokens: Set<String>): String {
        require(word.length in 2..32 && word.all { it.code in 0x3400..0x9fff }) { "当前唤醒模型支持 2–32 个汉字" }
        val transliterator = Transliterator.getInstance("Han-Latin")
        val initials = listOf("zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "r", "z", "c", "s", "y", "w")
        val pieces = word.flatMap { char ->
            val syllable = transliterator.transliterate(char.toString()).lowercase().trim()
            val initial = initials.firstOrNull { syllable.startsWith(it) }
            if (initial == null) listOf(syllable) else listOf(initial, syllable.removePrefix(initial))
        }
        require(pieces.all { it.isNotEmpty() && it in tokens }) { "唤醒词拼音不在模型字典中，请在后台修改" }
        return pieces.joinToString(" ") + " @$word"
    }
}

class NativeModels(root: String) : AutoCloseable {
    private var handle = create(root)
    init { check(handle != 0L) { "端侧模型加载失败" } }
    private fun activeHandle(): Long { check(handle != 0L) { "端侧模型已关闭" }; return handle }
    @Synchronized fun reset(keyword: String) = reset(activeHandle(), keyword)
    @Synchronized fun feed(samples: ShortArray, vad: Boolean, kws: Boolean): Int = feed(activeHandle(), samples, vad, kws) // bit 0 VAD; bit 1 KWS
    @Synchronized fun embedding(samples: ShortArray): FloatArray = embedding(activeHandle(), samples)
    @Synchronized fun segments(samples: ShortArray): FloatArray = segments(activeHandle(), samples)
    @Synchronized fun matches(vector: FloatArray, candidates: List<FloatArray>): FloatArray = matches(activeHandle(), vector, candidates.toTypedArray())
    @Synchronized override fun close() { if (handle != 0L) { destroy(handle); handle = 0 } }
    private external fun create(root: String): Long
    private external fun destroy(handle: Long)
    private external fun reset(handle: Long, keyword: String)
    private external fun feed(handle: Long, samples: ShortArray, vad: Boolean, kws: Boolean): Int
    private external fun embedding(handle: Long, samples: ShortArray): FloatArray
    private external fun segments(handle: Long, samples: ShortArray): FloatArray
    private external fun matches(handle: Long, vector: FloatArray, candidates: Array<FloatArray>): FloatArray
    companion object { init { System.loadLibrary("crabot_voice") } }
}

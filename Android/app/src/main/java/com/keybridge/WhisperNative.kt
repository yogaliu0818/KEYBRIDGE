package com.keybridge

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object WhisperNative {
    init { System.loadLibrary("keybridge-whisper") }
    external fun transcribe(path: String, audio: FloatArray): ByteArray?
}

class VoiceEngine(private val context: Context) {
    private val recording = AtomicBoolean(false)
    private val startLock = Any()
    @Volatile private var recorder: AudioRecord? = null
    private val models = mapOf("tiny" to "bd577a113a864445d4c299885e0cb97d4ba92b5f", "base" to "465707469ff3a37a2b9b8d8f89f2f99de7299dac")
    fun model(name: String): File = File(context.filesDir, "whisper-$name.bin")
    fun exists(name: String) = model(name).isFile
    fun prepare(): Boolean = recording.compareAndSet(false, true)
    fun stop() { synchronized(startLock) { recording.set(false); try { recorder?.stop() } catch (_: Exception) {} } }
    fun delete(name: String) = model(name).delete()
    suspend fun download(name: String): Unit = withContext(Dispatchers.IO) {
        val expected = models[name] ?: error("未知模型")
        val temp = File(context.filesDir, "whisper-$name.tmp")
        val url = URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$name.bin")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 20000; conn.readTimeout = 30000; conn.instanceFollowRedirects = true
        try {
            conn.connect(); check(conn.responseCode in 200..299) { "下載失敗：HTTP ${conn.responseCode}" }
            val digest = MessageDigest.getInstance("SHA-1")
            conn.inputStream.use { input -> temp.outputStream().use { out ->
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n); out.write(buffer, 0, n) }
            } }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(hash == expected) { "模型校驗失敗" }
            check(temp.renameTo(model(name))) { "無法儲存模型" }
        } finally { conn.disconnect(); temp.delete() }
    }
    @SuppressLint("MissingPermission")
    suspend fun record(): FloatArray = withContext(Dispatchers.IO) {
        var audio: AudioRecord? = null
        try {
            check(recording.get()) { "錄音已取消" }
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "手機不支援所需錄音格式" }
            audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
            synchronized(startLock) {
                check(audio.state == AudioRecord.STATE_INITIALIZED) { "無法啟動麥克風" }
                check(recording.get()) { "錄音已取消" }
                recorder = audio
                audio.startRecording()
            }
            val samples = ShortArray(16000 * 60)
            var count = 0
            while (recording.get() && count < samples.size) {
                val n = audio.read(samples, count, minOf(2048, samples.size - count))
                if (n <= 0) { if (recording.get()) error("讀取麥克風失敗：$n") else break }
                count += n
            }
            check(count > 0) { "沒有錄到聲音" }
            FloatArray(count) { samples[it] / 32768.0f }
        } finally {
            synchronized(startLock) {
                recording.set(false); recorder = null
                try { audio?.stop() } catch (_: Exception) {}
                audio?.release()
            }
        }
    }

    suspend fun transcribe(name: String, samples: FloatArray): String = withContext(Dispatchers.Default) {
        val bytes = WhisperNative.transcribe(model(name).absolutePath, samples) ?: error("模型載入或辨識失敗")
        bytes.toString(Charsets.UTF_8).trim()
    }
}

package com.grinya.voicesampleprep.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Запись с микрофона: стерео 48 кГц, 16-bit PCM во float[]. Пишет в память. Отдаёт уровень (пик) для UI.
 */
class Recorder {

    interface Listener {
        /** Пиковый уровень 0..1 (RMS по блоку), шлётся ~30 раз/сек с UI-потока. */
        fun onLevel(rms: Float)

        /** Запись завершена. elapsedMs — длительность. */
        fun onFinished(pcm: FloatArray, elapsedMs: Int)

        /** Ошибка. */
        fun onError(msg: String)
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val running = AtomicBoolean(false)
    private val ui = Handler(Looper.getMainLooper())
    private var listener: Listener? = null
    private var startTime: Long = 0
    private var ns: NoiseSuppressor? = null
    private var noiseSuppressionEnabled = true // по умолчанию включено

    fun setListener(l: Listener?) {
        this.listener = l
    }

    fun isRecording(): Boolean = running.get()

    fun start() {
        if (running.get()) return

        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf < 0) {
            listener?.onError("getMinBufferSize: $minBuf")
            return
        }
        val bufSize = maxOf(minBuf, 16384)

        try {
            val created = AudioRecord(
                MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            record = created
            // Шумоподавление (если доступно на устройстве и включено)
            if (noiseSuppressionEnabled && NoiseSuppressor.isAvailable()) {
                try {
                    val suppressor = NoiseSuppressor.create(created.audioSessionId)
                    ns = suppressor
                    suppressor?.setEnabled(true)
                } catch (e: Exception) {
                    Log.e("RECORDER", "NS init failed", e)
                }
            }
        } catch (e: Exception) {
            listener?.onError("AudioRecord init: ${e.message}")
            return
        }
        val rec = record
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            record = null
            listener?.onError("AudioRecord not initialized")
            return
        }

        running.set(true)
        startTime = System.currentTimeMillis()

        val readBuf = bufSize / 2 // shorts
        val tmp = ShortArray(readBuf)

        val t = Thread(Runnable {
            rec.startRecording()
            val bos = ByteArrayOutputStream()
            val byteBuf = ByteArray(readBuf * 2)

            while (running.get()) {
                val n = rec.read(tmp, 0, tmp.size)
                if (n <= 0) continue

                // пишем в byte buffer для скорости
                for (i in 0 until n) {
                    val s = tmp[i].toInt()
                    byteBuf[i * 2] = (s and 0xFF).toByte()
                    byteBuf[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                }
                bos.write(byteBuf, 0, n * 2)

                // RMS для индикатора (сэмплируем каждый 4-й сэмпл для скорости)
                var sum = 0.0
                var count = 0
                for (i in 0 until n step 4) {
                    val v = tmp[i] / 32768f
                    sum += (v * v).toDouble()
                    count++
                }
                val rms = if (count > 0) sqrt(sum / count).toFloat() else 0f
                ui.post { listener?.onLevel(rms) }
            }

            try {
                rec.stop()
            } catch (ignored: Exception) {
            }
            rec.release()
            record = null

            val elapsed = (System.currentTimeMillis() - startTime).toInt()

            // конвертируем byte[] (LE 16-bit interleaved stereo) в float[]
            val bytes = bos.toByteArray()
            val sampleCount = bytes.size / 2
            val pcm = FloatArray(sampleCount)
            for (i in 0 until sampleCount) {
                val s = (((bytes[i * 2 + 1].toInt() and 0xFF) shl 8) or
                    (bytes[i * 2].toInt() and 0xFF)).toShort()
                pcm[i] = s / 32768f
            }

            ui.post { listener?.onFinished(pcm, elapsed) }
        }, "AudioRecorder")
        thread = t
        t.start()
    }

    fun stop() {
        running.set(false)
        ns?.let {
            try {
                it.release()
            } catch (ignored: Exception) {
            }
        }
        ns = null
    }

    fun release() {
        running.set(false)
        this.listener = null
    }

    fun setNoiseSuppressionEnabled(enabled: Boolean) {
        this.noiseSuppressionEnabled = enabled
    }

    companion object {
        private const val RATE = 48000
    }
}

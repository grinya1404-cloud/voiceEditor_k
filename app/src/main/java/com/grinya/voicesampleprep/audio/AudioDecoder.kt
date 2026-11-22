package com.grinya.voicesampleprep.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.grinya.voicesampleprep.util.AudioOps
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

object AudioDecoder {

    const val TARGET_RATE = 48000

    class Result {
        @JvmField var pcm: FloatArray = FloatArray(0) // уже 48к стерео
        @JvmField var sampleRate: Int = 0 // = 48000
        @JvmField var channels: Int = 0 // = 2

        // НОВЫЕ ПОЛЯ:
        @JvmField var originalSampleRate: Int = 0
        @JvmField var originalChannels: Int = 0
        @JvmField var originalBits: Int = 0 // 0, если неизвестно
        @JvmField var originalFormat: String? = null // "wav", "mp3", "m4a", "flac"
    }

    @JvmStatic
    @Throws(IOException::class)
    fun decode(f: File): Result {
        val name = f.name.lowercase()
        if (name.endsWith(".wav")) {
            val w = WavReader.read(f)
            var pcm = w.pcm
            if (w.sampleRate != TARGET_RATE) {
                pcm = AudioOps.resample(pcm, w.sampleRate, TARGET_RATE)
            }
            val r = Result()
            r.pcm = pcm
            r.sampleRate = TARGET_RATE
            r.originalSampleRate = w.sampleRate
            r.originalChannels = w.channels
            r.originalBits = w.bits
            r.originalFormat = "wav"
            return r
        }
        return decodeViaCodec(f)
    }

    @Throws(IOException::class)
    private fun decodeViaCodec(f: File): Result {
        val ex = MediaExtractor()
        val fileName = f.name.lowercase()
        val dotIdx = fileName.lastIndexOf('.')
        val ext = if (dotIdx > 0) fileName.substring(dotIdx + 1) else "unknown"

        try {
            ex.setDataSource(f.absolutePath)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val mf = ex.getTrackFormat(i)
                val mime = mf.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    track = i
                    fmt = mf
                    break
                }
            }
            if (track < 0) throw IOException("No audio track")
            val format: MediaFormat = fmt ?: throw IOException("No audio track")
            ex.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME)
            val codec = MediaCodec.createDecoderByType(mime!!)
            codec.configure(format, null, null, 0)
            codec.start()

            // соберём всё в список short-массивов, потом объединим
            val chunks = ArrayList<ShortArray>()
            var totalShorts = 0L

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)
                        val sz = ex.readSampleData(inBuf!!, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val sb = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                        val arr = ShortArray(sb.remaining())
                        sb.get(arr)
                        chunks.add(arr)
                        totalShorts += arr.size
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
                // INFO_OUTPUT_FORMAT_CHANGED: игнорируем, читаем каналы из исходного fmt
            }

            codec.stop()
            codec.release()

            // сколько каналов в исходнике?
            val srcCh = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }
            val srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }

            val totalFrames = (totalShorts / maxOf(1, srcCh)).toInt()
            var stereo = FloatArray(totalFrames * 2)
            var frame = 0
            for (arr in chunks) {
                var i = 0
                while (i < arr.size && frame < totalFrames) {
                    if (srcCh == 1) {
                        val v = arr[i] / 32768f
                        stereo[frame * 2] = v
                        stereo[frame * 2 + 1] = v
                        i += 1
                        frame++
                    } else {
                        if (i + 1 >= arr.size) break
                        stereo[frame * 2] = arr[i] / 32768f
                        stereo[frame * 2 + 1] = arr[i + 1] / 32768f
                        i += 2
                        frame++
                    }
                }
            }

            if (srcRate != TARGET_RATE) {
                stereo = AudioOps.resample(stereo, srcRate, TARGET_RATE)
            }

            val r = Result()
            r.pcm = stereo
            r.sampleRate = TARGET_RATE
            r.originalSampleRate = srcRate
            r.originalChannels = srcCh
            r.originalBits = 0 // MP3 не имеет фиксированной битности
            r.originalFormat = ext // "mp3", "m4a", "flac" (из имени файла)
            return r
        } finally {
            ex.release()
        }
    }
}

package com.grinya.voicesampleprep.audio

import com.grinya.voicesampleprep.util.AudioOps
import com.grinya.voicesampleprep.util.Denoiser
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object WavWriter {

    /**
     * @param pcm     interleaved stereo (L,R,L,R...)
     * @param srcRate sample rate исходного pcm
     * @param dstRate sample rate для файла (если != srcRate — пересэмплируем)
     * @param bits    16 или 24
     */
    @JvmStatic
    @Throws(IOException::class)
    fun write(
        f: File, pcm: FloatArray, srcRate: Int, dstRate: Int, bits: Int,
        isStereoMono: Boolean, isTrueMono: Boolean, normalize: Boolean, fade: Boolean,
        trimSilence: Boolean, denoise: Boolean,
        fadeMs: Int = 5, normalizeTarget: Float = 0.9f
    ) {
        var data = pcm
        var rate = srcRate

        // 1. Ресемплинг
        if (dstRate != srcRate) {
            data = AudioOps.resample(data, srcRate, dstRate)
            rate = dstRate
        }

        // 2. Denoise
        if (denoise) {
            data = Denoiser.denoiseStereo(data)
        }

        // 2.5 Сведение каналов
        if (isTrueMono) {
            data = AudioOps.toMonoSmart(data)
        } else if (isStereoMono) {
            val mono = AudioOps.toMonoSmart(data)
            data = FloatArray(mono.size * 2)
            for (i in mono.indices) {
                data[i * 2] = mono[i]
                data[i * 2 + 1] = mono[i]
            }
        }

        // 3. Trim Silence
        if (trimSilence) {
            val ch = if (isTrueMono) 1 else 2
            data = AudioOps.trimSilence(data, 0.003f, ch)
        }

        // 4. Normalize
        if (normalize) {
            data = AudioOps.normalizePeak(data, normalizeTarget) // <-- Используем параметр
        }

        // 5. Fade
        if (fade) {
            val ff = rate * fadeMs / 1000 // <-- Используем параметр
            data = AudioOps.fadeIn(data, ff, true, true)
            data = AudioOps.fadeOut(data, ff, true, true)
        }

        val channels = if (isTrueMono) 1 else 2
        val bytesPerSample = bits / 8
        val dataBytes = data.size * bytesPerSample

        BufferedOutputStream(FileOutputStream(f), 65536).use { out ->
            writeAscii(out, "RIFF")
            writeLE32(out, 36 + dataBytes)
            writeAscii(out, "WAVE")

            writeAscii(out, "fmt ")
            writeLE32(out, 16)
            writeLE16(out, 1)
            writeLE16(out, channels)
            writeLE32(out, rate)
            writeLE32(out, rate * channels * bytesPerSample)
            writeLE16(out, channels * bytesPerSample)
            writeLE16(out, bits)

            writeAscii(out, "data")
            writeLE32(out, dataBytes)

            if (bits == 16) {
                val buf = ByteArray(8192)
                var bi = 0
                for (v in data) {
                    var s = Math.round(v * 32767f)
                    if (s > 32767) s = 32767
                    if (s < -32768) s = -32768
                    buf[bi++] = (s and 0xFF).toByte()
                    buf[bi++] = ((s shr 8) and 0xFF).toByte()
                    if (bi == buf.size) {
                        out.write(buf, 0, bi)
                        bi = 0
                    }
                }
                if (bi > 0) out.write(buf, 0, bi)
            } else {
                val buf = ByteArray(8190)
                var bi = 0
                for (v in data) {
                    var s = Math.round(v * 8388607f)
                    if (s > 8388607) s = 8388607
                    if (s < -8388608) s = -8388608
                    buf[bi++] = (s and 0xFF).toByte()
                    buf[bi++] = ((s shr 8) and 0xFF).toByte()
                    buf[bi++] = ((s shr 16) and 0xFF).toByte()
                    if (bi == buf.size) {
                        out.write(buf, 0, bi)
                        bi = 0
                    }
                }
                if (bi > 0) out.write(buf, 0, bi)
            }
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun write(
        f: File, pcm: FloatArray, srcRate: Int, dstRate: Int, bits: Int,
        isStereoMono: Boolean, isTrueMono: Boolean, normalize: Boolean, fade: Boolean,
        trimSilence: Boolean,
        fadeMs: Int = 5, normalizeTarget: Float = 0.9f
    ) {
        write(
            f, pcm, srcRate, dstRate, bits, isStereoMono, isTrueMono, normalize, fade,
            trimSilence, false, fadeMs, normalizeTarget
        )
    }

    @Throws(IOException::class)
    private fun writeAscii(out: BufferedOutputStream, s: String) {
        out.write(s.toByteArray(Charsets.US_ASCII))
    }

    @Throws(IOException::class)
    private fun writeLE16(out: BufferedOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    @Throws(IOException::class)
    private fun writeLE32(out: BufferedOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }
}

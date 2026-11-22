package com.grinya.voicesampleprep.audio

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Минимальный WAV-ридер: PCM 8/16/24/32-bit int, 32-bit float.
 * Возвращает всегда interleaved stereo float[] (L,R,L,R...). Моно дублируется.
 */
object WavReader {

    class Result {
        @JvmField var pcm: FloatArray = FloatArray(0)
        @JvmField var sampleRate: Int = 0
        @JvmField var channels: Int = 0
        @JvmField var bits: Int = 0
    }

    @JvmStatic
    @Throws(IOException::class)
    fun read(f: File): Result {
        RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < 44) throw IOException("Too short WAV")

            val hdr = ByteArray(12)
            raf.readFully(hdr)
            if (String(hdr, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(hdr, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) {
                throw IOException("Not a WAV file")
            }

            var format = 0
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var dataOffset = -1L
            var dataSize = -1L

            while (raf.filePointer + 8 <= raf.length()) {
                val ch = ByteArray(8)
                raf.readFully(ch)
                val id = String(ch, 0, 4, Charsets.US_ASCII)
                val size = le32(ch, 4)
                val next = raf.filePointer + (size.toLong() and 0xFFFFFFFFL)

                if ("fmt " == id) {
                    val fmt = ByteArray(minOf(size, 16))
                    raf.readFully(fmt)
                    format = le16(fmt, 0)
                    channels = le16(fmt, 2)
                    sampleRate = le32(fmt, 4)
                    bitsPerSample = le16(fmt, 14)
                    raf.seek(next)
                } else if ("data" == id) {
                    dataOffset = raf.filePointer
                    dataSize = size.toLong() and 0xFFFFFFFFL
                    if (dataOffset + dataSize > raf.length()) {
                        dataSize = raf.length() - dataOffset
                    }
                    break
                } else {
                    raf.seek(next)
                }
            }

            if (dataOffset < 0) throw IOException("No data chunk")
            if (channels < 1 || channels > 2) throw IOException("Unsupported channels: $channels")
            if (format != 1 && format != 3) throw IOException("Unsupported format: $format")

            val bytesPerSample = bitsPerSample / 8
            if (bytesPerSample < 1 || bytesPerSample > 4) {
                throw IOException("Unsupported bps: $bitsPerSample")
            }

            val frames = dataSize / (bytesPerSample.toLong() * channels)
            // C7: предупреждаем, если обрезаем
            val maxFrames = (Int.MAX_VALUE / 4).toLong()
            if (frames > maxFrames) {
                Log.w("WAV", "Too many frames ($frames), обрезаем до $maxFrames")
            }
            val frameCount = minOf(frames, maxFrames).toInt()

            val monoOrStereo = FloatArray(frameCount * channels)
            val buf = ByteArray(8192)
            var outIdx = 0
            var remaining = frameCount.toLong() * channels * bytesPerSample
            raf.seek(dataOffset)
            var carry = 0
            val carryBuf = ByteArray(4)

            while (remaining > 0) {
                val toRead = minOf(buf.size.toLong(), remaining).toInt()
                val n = raf.read(buf, 0, toRead)
                if (n <= 0) break
                remaining -= n

                var i = 0
                while (i < n) {
                    val need = bytesPerSample
                    val have = n - i
                    if (carry + have < need) {
                        System.arraycopy(buf, i, carryBuf, carry, have)
                        carry += have
                        i = n
                        continue
                    }
                    val takeFromBuf = minOf(need - carry, have)
                    System.arraycopy(buf, i, carryBuf, carry, takeFromBuf)
                    carry += takeFromBuf
                    i += takeFromBuf

                    val b0 = carryBuf[0].toInt() and 0xFF
                    val b1 = carryBuf[1].toInt() and 0xFF
                    val b2 = carryBuf[2].toInt() and 0xFF
                    val b3 = carryBuf[3].toInt() and 0xFF
                    val v: Float = if (format == 3 && bitsPerSample == 32) {
                        java.lang.Float.intBitsToFloat((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0)
                    } else if (bitsPerSample == 8) {
                        (b0 - 128) / 128f
                    } else if (bitsPerSample == 16) {
                        val s = ((b1 shl 8) or b0).toShort().toInt()
                        s / 32768f
                    } else if (bitsPerSample == 24) {
                        var s = (b2 shl 16) or (b1 shl 8) or b0
                        if ((s and 0x800000) != 0) s = s or (0xFF shl 24)
                        s / 8388608f
                    } else {
                        val s = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
                        (s / 2147483648.0).toFloat()
                    }
                    carry = 0

                    if (outIdx < monoOrStereo.size) {
                        monoOrStereo[outIdx] = v
                        outIdx++
                    }
                }
            }

            val actualFrames = outIdx / channels
            val r = Result()
            r.sampleRate = sampleRate
            r.channels = channels
            r.bits = bitsPerSample

            val pcm = FloatArray(actualFrames * 2)
            if (channels == 2) {
                System.arraycopy(monoOrStereo, 0, pcm, 0, actualFrames * 2)
            } else {
                for (i in 0 until actualFrames) {
                    val s = monoOrStereo[i]
                    pcm[i * 2] = s
                    pcm[i * 2 + 1] = s
                }
            }
            r.pcm = pcm
            return r
        }
    }

    private fun le16(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun le32(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}

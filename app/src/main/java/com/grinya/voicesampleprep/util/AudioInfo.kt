package com.grinya.voicesampleprep.util

import com.grinya.voicesampleprep.model.AudioMeta
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

class AudioInfo {
    var peakDB = 0f
    var rmsDB = 0f
    var clipping = false
    var balanceLR = 0f
    var silenceStartMs = 0
    var silenceEndMs = 0
    var totalFrames = 0
    var sampleRate = 0
    var channels = 0
    var originalSampleRate = 0
    var originalChannels = 0
    var originalBits = 0
    var originalFormat: String? = null

    companion object {

        @JvmStatic
        fun analyze(pcm: FloatArray, sampleRate: Int): AudioInfo = analyze(pcm, sampleRate, 2)

        /** G1: channels — параметр, не хардкод. */
        @JvmStatic
        fun analyze(pcm: FloatArray, sampleRate: Int, channels: Int): AudioInfo {
            val info = AudioInfo()
            info.sampleRate = sampleRate
            info.channels = channels
            info.totalFrames = pcm.size / channels

            if (pcm.isEmpty()) return info

            var peakL = 0f
            var peakR = 0f
            var sumL = 0.0
            var sumR = 0.0
            var count = 0L
            var clipping = false

            for (i in 0 until pcm.size - 1 step 2) {
                val l = pcm[i]
                val r = pcm[i + 1]
                val absL = abs(l)
                val absR = abs(r)
                if (absL > peakL) peakL = absL
                if (absR > peakR) peakR = absR
                if (absL >= 1f || absR >= 1f) clipping = true
                sumL += l * l
                sumR += r * r
                count++
            }

            val peak = maxOf(peakL, peakR)
            info.peakDB = (20 * log10(maxOf(peak.toDouble(), 1e-9))).toFloat()
            info.clipping = clipping

            val rmsL = sqrt(sumL / count)
            val rmsR = sqrt(sumR / count)
            val rms = (rmsL + rmsR) / 2
            info.rmsDB = (20 * log10(maxOf(rms, 1e-9))).toFloat()

            val dbL = (20 * log10(maxOf(rmsL, 1e-9))).toFloat()
            val dbR = (20 * log10(maxOf(rmsR, 1e-9))).toFloat()
            info.balanceLR = dbL - dbR

            var startSilence = 0
            for (i in 0 until pcm.size step 2) {
                if (abs(pcm[i]) > 0.001f || abs(pcm[i + 1]) > 0.001f) break
                startSilence++
            }
            info.silenceStartMs = startSilence * 1000 / sampleRate

            var endSilence = 0
            for (i in pcm.size - 2 downTo 0 step 2) {
                if (abs(pcm[i]) > 0.001f || abs(pcm[i + 1]) > 0.001f) break
                endSilence++
            }
            info.silenceEndMs = endSilence * 1000 / sampleRate

            return info
        }

        @JvmStatic
        fun analyze(pcm: FloatArray, sampleRate: Int, meta: AudioMeta?): AudioInfo {
            val info = analyze(pcm, sampleRate)
            if (meta != null) {
                info.originalSampleRate = meta.sampleRate
                info.originalChannels = meta.channels
                info.originalBits = meta.bits
                info.originalFormat = meta.format
            }
            return info
        }
    }
}

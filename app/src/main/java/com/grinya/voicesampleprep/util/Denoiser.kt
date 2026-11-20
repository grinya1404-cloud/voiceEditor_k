package com.grinya.voicesampleprep.util

import android.util.Log
import com.grinya.rnnoisewrapper.RnnoiseWrapper

object Denoiser {

    @JvmStatic
    fun denoiseStereo(pcm: FloatArray): FloatArray {
        if (pcm.isEmpty()) return pcm

        val frames = pcm.size / 2
        val left = FloatArray(frames)
        val right = FloatArray(frames)

        for (i in 0 until frames) {
            left[i] = pcm[i * 2]
            right[i] = pcm[i * 2 + 1]
        }

        // G5: один wrapper на оба канала
        var wrapper: RnnoiseWrapper? = null
        return try {
            val w = RnnoiseWrapper.create()
            wrapper = w
            val blockSize = w.frameSize

            val leftDenoised = denoiseMono(w, blockSize, left)
            val rightDenoised = denoiseMono(w, blockSize, right)

            val out = FloatArray(pcm.size)
            for (i in 0 until frames) {
                out[i * 2] = leftDenoised[i]
                out[i * 2 + 1] = rightDenoised[i]
            }
            out
        } catch (e: Throwable) {
            Log.e("DENOISER", "Ошибка шумодава", e)
            pcm
        } finally {
            wrapper?.close()
        }
    }

    private fun denoiseMono(wrapper: RnnoiseWrapper, blockSize: Int, mono: FloatArray): FloatArray {
        if (mono.isEmpty()) return mono

        val out = FloatArray(mono.size)
        // G5: переиспользуемые буферы
        val inputFrame = FloatArray(blockSize)
        val outputFrame = FloatArray(blockSize)

        for (i in 0 until mono.size step blockSize) {
            val n = minOf(blockSize, mono.size - i)

            inputFrame.fill(0f)
            outputFrame.fill(0f)
            mono.copyInto(inputFrame, 0, i, i + n)

            wrapper.processFrame(inputFrame, outputFrame)

            outputFrame.copyInto(out, i, 0, n)
        }
        return out
    }
}

package com.grinya.voicesampleprep.util

import android.util.Log
import cn.enaium.rnnoise.createRnnoise
import cn.enaium.rnnoise.Rnnoise

object Denoiser {

    @JvmStatic
    fun denoiseStereo(pcm: FloatArray): FloatArray {
        if (pcm.isEmpty()) return pcm

        val frames = pcm.size / 2
        // Разделяем на левый и правый каналы
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (i in 0 until frames) {
            left[i] = pcm[i * 2]
            right[i] = pcm[i * 2 + 1]
        }

        // Один экземпляр rnnoise на оба канала (как и раньше)
        return try {
            createRnnoise().use { rnnoise ->
                val leftDenoised = denoiseMono(rnnoise, left)
                val rightDenoised = denoiseMono(rnnoise, right)

                val out = FloatArray(pcm.size)
                for (i in 0 until frames) {
                    out[i * 2] = leftDenoised[i]
                    out[i * 2 + 1] = rightDenoised[i]
                }
                out
            }
        } catch (e: Throwable) {
            Log.e("DENOISER", "Ошибка шумодава", e)
            pcm // Возвращаем оригинал при ошибке
        }
    }

    private fun denoiseMono(rnnoise: Rnnoise, mono: FloatArray): FloatArray {
        if (mono.isEmpty()) return mono

        val frameSize = rnnoise.frameSize // 480
        val out = FloatArray(mono.size)
        val inputFrame = FloatArray(frameSize)
        val outputFrame = FloatArray(frameSize)

        for (i in mono.indices step frameSize) {
            val n = minOf(frameSize, mono.size - i)

            // Заполняем нулями хвост, если последний фрейм неполный
            inputFrame.fill(0f)
            outputFrame.fill(0f)
            mono.copyInto(inputFrame, 0, i, i + n)

            // processFrame возвращает вероятность речи (VAD), но нам важен output
            rnnoise.processFrame(inputFrame, outputFrame)

            // Копируем только реальные сэмплы (без «хвоста» нулей)
            outputFrame.copyInto(out, i, 0, n)
        }
        return out
    }
}
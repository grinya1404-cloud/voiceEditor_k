package com.grinya.voicesampleprep.util

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object AudioOps {

    /** Обрезка по индексам фреймов [startFrame, endFrame). */
    @JvmStatic
    fun trim(pcm: FloatArray, startFrame: Int, endFrame: Int): FloatArray {
        val ch = 2
        val from = max(0, startFrame) * ch
        val to = min(pcm.size / ch, endFrame) * ch
        if (to <= from) return FloatArray(0)
        return pcm.copyOfRange(from, to)
    }

    /** Удалить фрагмент [startFrame, endFrame), сдвинув остаток. */
    @JvmStatic
    fun deleteRange(pcm: FloatArray, startFrame: Int, endFrame: Int): FloatArray {
        val ch = 2
        val totalFrames = pcm.size / ch
        val s = max(0, min(startFrame, totalFrames))
        val e = max(s, min(endFrame, totalFrames))
        val removed = (e - s) * ch
        val out = FloatArray(pcm.size - removed)
        System.arraycopy(pcm, 0, out, 0, s * ch)
        System.arraycopy(pcm, e * ch, out, s * ch, pcm.size - e * ch)
        return out
    }

    /** Усиление на dB (может быть отрицательным). */
    @JvmStatic
    fun gain(pcm: FloatArray, dB: Float): FloatArray {
        val mul = 10.0.pow(dB / 20.0).toFloat()
        return FloatArray(pcm.size) { pcm[it] * mul }
    }

    /** Пиковая нормализация к target (0..1). */
    @JvmStatic
    fun normalizePeak(pcm: FloatArray, target: Float): FloatArray {
        var peak = 0f
        for (v in pcm) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak <= 1e-9f) return pcm.copyOf()
        val mul = target / peak
        return FloatArray(pcm.size) { pcm[it] * mul }
    }

    /** Свести в моно (пересчитывает массив: каждый фрейм — один сэмпл). */
    @JvmStatic
    fun toMono(pcm: FloatArray): FloatArray {
        val frames = pcm.size / 2
        return FloatArray(frames) { i -> (pcm[i * 2] + pcm[i * 2 + 1]) * 0.5f }
    }

    /** Линейный ресемплинг interleaved stereo. */
    @JvmStatic
    fun resample(pcm: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (srcRate == dstRate) return pcm.copyOf()
        val ch = 2
        val srcFrames = pcm.size / ch
        if (srcFrames == 0) return FloatArray(0)
        val dstFrames = Math.round(srcFrames.toDouble() * dstRate / srcRate).toInt()
        val out = FloatArray(dstFrames * ch)
        val ratio = srcRate.toDouble() / dstRate
        for (i in 0 until dstFrames) {
            val srcPos = i * ratio
            val i0 = srcPos.toInt()
            val i1 = min(i0 + 1, srcFrames - 1)
            val frac = (srcPos - i0).toFloat()
            for (c in 0 until ch) {
                val a = pcm[i0 * ch + c]
                val b = pcm[i1 * ch + c]
                out[i * ch + c] = a + (b - a) * frac
            }
        }
        return out
    }

    /** Длина в фреймах. */
    @JvmStatic
    fun frameCount(pcm: FloatArray): Int = pcm.size / 2

    /** Найти ближайший zero-crossing в стерео (по сумме каналов) к targetFrame. */
    @JvmStatic
    fun nearestZeroCrossing(pcm: FloatArray, targetFrame: Int): Int {
        val frames = frameCount(pcm)
        if (frames == 0) return 0
        val target = max(0, min(frames - 1, targetFrame))
        val search = min(2048, frames / 2)
        var best = target
        var bestDist = Int.MAX_VALUE
        for (d in 0..search) {
            val a = target - d
            val b = target + d
            if (a >= 0 && isZeroCross(pcm, a)) {
                if (d < bestDist) {
                    bestDist = d
                    best = a
                }
            }
            if (b < frames && isZeroCross(pcm, b)) {
                if (d < bestDist) {
                    bestDist = d
                    best = b
                }
            }
            if (bestDist <= 1) break
        }
        return best
    }

    private fun isZeroCross(pcm: FloatArray, frame: Int): Boolean {
        if (frame <= 0) return true
        val prev = pcm[(frame - 1) * 2] + pcm[(frame - 1) * 2 + 1]
        val cur = pcm[frame * 2] + pcm[frame * 2 + 1]
        return (prev <= 0f && cur >= 0f) || (prev >= 0f && cur <= 0f)
    }

    @JvmStatic
    fun silenceRange(
        pcm: FloatArray,
        startFrame: Int,
        endFrame: Int,
        applyL: Boolean,
        applyR: Boolean,
    ): FloatArray {
        val ch = 2
        val totalFrames = pcm.size / ch
        val s = max(0, min(startFrame, totalFrames))
        val e = max(s, min(endFrame, totalFrames))
        val out = pcm.copyOf()
        for (i in s * ch until e * ch step ch) {
            if (applyL) out[i] = 0f
            if (applyR) out[i + 1] = 0f
        }
        return out
    }

    @JvmStatic
    fun gainRange(
        pcm: FloatArray,
        startFrame: Int,
        endFrame: Int,
        dB: Float,
        applyL: Boolean,
        applyR: Boolean,
    ): FloatArray {
        val ch = 2
        val totalFrames = pcm.size / ch
        val s = max(0, min(startFrame, totalFrames))
        val e = max(s, min(endFrame, totalFrames))
        val mul = 10.0.pow(dB / 20.0).toFloat()
        val out = pcm.copyOf()
        for (i in s * ch until e * ch step ch) {
            if (applyL) out[i] *= mul
            if (applyR) out[i + 1] *= mul
        }
        return out
    }

    @JvmStatic
    fun normalizeRangePeak(
        pcm: FloatArray,
        startFrame: Int,
        endFrame: Int,
        target: Float,
        applyL: Boolean,
        applyR: Boolean,
    ): FloatArray {
        val ch = 2
        val totalFrames = pcm.size / ch
        val s = max(0, min(startFrame, totalFrames))
        val e = max(s, min(endFrame, totalFrames))
        var peak = 0f
        for (i in s * ch until e * ch step ch) {
            if (applyL) peak = max(peak, abs(pcm[i]))
            if (applyR) peak = max(peak, abs(pcm[i + 1]))
        }
        if (peak <= 1e-9f) return pcm.copyOf()
        val mul = target / peak
        val out = pcm.copyOf()
        for (i in s * ch until e * ch step ch) {
            if (applyL) out[i] *= mul
            if (applyR) out[i + 1] *= mul
        }
        return out
    }

    @JvmStatic
    fun fadeIn(pcm: FloatArray, fadeFrames: Int, applyL: Boolean, applyR: Boolean): FloatArray {
        val ch = 2
        val out = pcm.copyOf()
        val totalFrames = out.size / ch
        val n = min(fadeFrames, totalFrames)
        for (i in 0 until n) {
            // C2: последний сэмпл достигает 1.0
            val g = if (n > 1) i.toFloat() / (n - 1) else 1f
            if (applyL) out[i * ch] *= g
            if (applyR) out[i * ch + 1] *= g
        }
        return out
    }

    @JvmStatic
    fun fadeOut(pcm: FloatArray, fadeFrames: Int, applyL: Boolean, applyR: Boolean): FloatArray {
        val ch = 2
        val out = pcm.copyOf()
        val totalFrames = out.size / ch
        val n = min(fadeFrames, totalFrames)
        for (i in 0 until n) {
            // C2: последний сэмпл достигает 0.0
            val g = if (n > 1) 1f - i.toFloat() / (n - 1) else 0f
            val idx = (totalFrames - n + i) * ch
            if (applyL) out[idx] *= g
            if (applyR) out[idx + 1] *= g
        }
        return out
    }

    @JvmStatic
    fun reverse(pcm: FloatArray, applyL: Boolean, applyR: Boolean): FloatArray {
        val ch = 2
        val n = pcm.size / ch
        val out = FloatArray(pcm.size)
        for (i in 0 until n) {
            val srcIdx = (n - 1 - i) * ch
            val dstIdx = i * ch
            out[dstIdx] = if (applyL) pcm[srcIdx] else pcm[dstIdx]
            out[dstIdx + 1] = if (applyR) pcm[srcIdx + 1] else pcm[dstIdx + 1]
        }
        return out
    }

    @JvmStatic
    fun reverseRange(
        pcm: FloatArray,
        startFrame: Int,
        endFrame: Int,
        applyL: Boolean,
        applyR: Boolean,
    ): FloatArray {
        val ch = 2
        val totalFrames = pcm.size / ch
        val s = max(0, min(startFrame, totalFrames))
        val e = max(s, min(endFrame, totalFrames))
        val out = pcm.copyOf()

        val n = e - s
        for (i in 0 until n / 2) {
            val leftIdx = (s + i) * ch
            val rightIdx = (e - 1 - i) * ch

            if (applyL) {
                val tmp = out[leftIdx]
                out[leftIdx] = out[rightIdx]
                out[rightIdx] = tmp
            }
            if (applyR) {
                val tmp = out[leftIdx + 1]
                out[leftIdx + 1] = out[rightIdx + 1]
                out[rightIdx + 1] = tmp
            }
        }
        return out
    }

    @JvmStatic
    fun invertPhase(
        pcm: FloatArray,
        startFrame: Int,
        endFrame: Int,
        applyL: Boolean,
        applyR: Boolean,
    ): FloatArray {
        val ch = 2
        val totalFrames = pcm.size / ch
        val s = max(0, min(startFrame, totalFrames))
        val e = max(s, min(endFrame, totalFrames))
        val out = pcm.copyOf()
        for (i in s * ch until e * ch step ch) {
            if (applyL) out[i] *= -1f
            if (applyR) out[i + 1] *= -1f
        }
        return out
    }

    /**
     * C4: сведение в моно с учётом корреляции.
     * correlation < 0 (противофаза) → берём максимум по модулю (без гашения).
     * correlation >= 0 → Half-Power (1/√2).
     * Если пик > 1.0 — нормализуем до 1.0.
     */
    @JvmStatic
    fun toMonoSmart(pcm: FloatArray): FloatArray {
        val frames = pcm.size / 2
        if (frames == 0) return FloatArray(0)

        var sumLR = 0.0
        var sumL2 = 0.0
        var sumR2 = 0.0
        for (i in 0 until pcm.size step 2) {
            sumLR += pcm[i] * pcm[i + 1]
            sumL2 += pcm[i] * pcm[i]
            sumR2 += pcm[i + 1] * pcm[i + 1]
        }
        val denom = sqrt(sumL2 * sumR2)
        val correlation = if (denom > 1e-9) sumLR / denom else 0.0

        val out = FloatArray(frames)
        var peak = 0f

        if (correlation < 0) {
            for (i in 0 until frames) {
                val l = pcm[i * 2]
                val r = pcm[i * 2 + 1]
                out[i] = if (abs(l) >= abs(r)) l else r
                if (abs(out[i]) > peak) peak = abs(out[i])
            }
        } else {
            val k = 0.7071f
            for (i in 0 until frames) {
                out[i] = (pcm[i * 2] + pcm[i * 2 + 1]) * k
                if (abs(out[i]) > peak) peak = abs(out[i])
            }
        }

        if (peak > 1.0f) {
            val mul = 1.0f / peak
            for (i in 0 until frames) {
                out[i] *= mul
            }
        }

        return out
    }

    @JvmStatic
    fun fadeInRange(
        pcm: FloatArray,
        startFrame: Int,
        endFrame: Int,
        fadeFrames: Int,
        applyL: Boolean,
        applyR: Boolean,
    ): FloatArray {
        val ch = 2
        val total = pcm.size / ch
        val s = max(0, min(startFrame, total))
        val e = max(s, min(endFrame, total))
        val n = min(fadeFrames, e - s)
        if (n <= 0) return pcm.copyOf()

        val out = pcm.copyOf()
        for (i in 0 until n) {
            // C2: последний сэмпл достигает 1.0
            val g = if (n > 1) i.toFloat() / (n - 1) else 1f
            val idx = (s + i) * ch
            if (applyL) out[idx] *= g
            if (applyR) out[idx + 1] *= g
        }
        return out
    }

    @JvmStatic
    fun fadeOutRange(
        pcm: FloatArray,
        startFrame: Int,
        endFrame: Int,
        fadeFrames: Int,
        applyL: Boolean,
        applyR: Boolean,
    ): FloatArray {
        val ch = 2
        val total = pcm.size / ch
        val s = max(0, min(startFrame, total))
        val e = max(s, min(endFrame, total))
        val n = min(fadeFrames, e - s)
        if (n <= 0) return pcm.copyOf()

        val out = pcm.copyOf()
        for (i in 0 until n) {
            // C2: последний сэмпл достигает 0.0
            val g = if (n > 1) 1f - i.toFloat() / (n - 1) else 0f
            val idx = (e - n + i) * ch
            if (applyL) out[idx] *= g
            if (applyR) out[idx + 1] *= g
        }
        return out
    }

    /**
     * C1: обрезает тишину в начале/конце.
     * Если весь файл — тишина, возвращает пустой массив (а не весь файл).
     */
    @JvmStatic
    fun trimSilence(pcm: FloatArray, threshold: Float, channels: Int): FloatArray {
        if (pcm.isEmpty() || channels <= 0) return pcm
        val total = pcm.size / channels

        var s = -1
        for (i in 0 until total) {
            if (!isSilentFrame(pcm, i, channels, threshold)) {
                s = i
                break
            }
        }

        // весь файл — тишина
        if (s < 0) return FloatArray(0)

        var e = -1
        for (i in total - 1 downTo s) {
            if (!isSilentFrame(pcm, i, channels, threshold)) {
                e = i + 1
                break
            }
        }

        if (e <= s) return FloatArray(0)
        val out = FloatArray((e - s) * channels)
        System.arraycopy(pcm, s * channels, out, 0, out.size)
        return out
    }

    private fun isSilentFrame(pcm: FloatArray, frame: Int, channels: Int, threshold: Float): Boolean {
        for (c in 0 until channels) {
            if (abs(pcm[frame * channels + c]) >= threshold) return false
        }
        return true
    }

    /**
     * High-Pass фильтр (Баттерворт 2-го порядка).
     *
     * @param pcm        стерео-массив (L,R,L,R...)
     * @param sampleRate частота дискретизации (48000)
     * @param cutoffHz   частота среза (например, 80 Гц)
     * @param applyL     применять к левому каналу
     * @param applyR     применять к правому каналу
     */
    @JvmStatic
    fun highPass(
        pcm: FloatArray,
        sampleRate: Int,
        cutoffHz: Float,
        applyL: Boolean,
        applyR: Boolean,
    ): FloatArray {
        val frames = pcm.size / 2
        val out = pcm.copyOf()

        val w0 = 2.0 * Math.PI * cutoffHz / sampleRate
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0 * sqrt(2.0))

        var b0 = (1 + cosW0) / 2
        var b1 = -(1 + cosW0)
        var b2 = (1 + cosW0) / 2
        val a0 = 1 + alpha
        var a1 = -2 * cosW0
        var a2 = 1 - alpha

        b0 /= a0
        b1 /= a0
        b2 /= a0
        a1 /= a0
        a2 /= a0

        if (applyL) {
            var x1 = 0.0
            var x2 = 0.0
            var y1 = 0.0
            var y2 = 0.0
            for (i in 0 until frames) {
                val x = out[i * 2].toDouble()
                val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1
                x1 = x
                y2 = y1
                y1 = y
                out[i * 2] = y.toFloat()
            }
        }
        if (applyR) {
            var x1 = 0.0
            var x2 = 0.0
            var y1 = 0.0
            var y2 = 0.0
            for (i in 0 until frames) {
                val x = out[i * 2 + 1].toDouble()
                val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1
                x1 = x
                y2 = y1
                y1 = y
                out[i * 2 + 1] = y.toFloat()
            }
        }
        return out
    }

    /**
     * G7: VAD-гейт. Заглушает участки, где RMS ниже thresholdDb (например, -40 dB).
     * holdMs — сколько мс держать «открытым» после последнего громкого фрейма
     * (чтобы не рубить хвосты слов).
     */
    @JvmStatic
    fun vadGate(pcm: FloatArray, sampleRate: Int, thresholdDb: Float, holdMs: Int): FloatArray {
        val ch = 2
        val frames = pcm.size / ch
        if (frames == 0) return pcm

        val out = pcm.copyOf()
        val threshold = 10.0.pow(thresholdDb / 20.0).toFloat()
        val holdFrames = max(1, holdMs * sampleRate / 1000)
        val window = max(1, sampleRate / 100) // 10 мс

        var open = false
        var holdLeft = 0

        for (start in 0 until frames step window) {
            val end = min(start + window, frames)
            var sum = 0.0
            var count = 0
            for (i in start until end) {
                val l = pcm[i * ch]
                val r = pcm[i * ch + 1]
                sum += l * l + r * r
                count += 2
            }
            val rms = if (count > 0) sqrt(sum / count).toFloat() else 0f

            if (rms >= threshold) {
                open = true
                holdLeft = holdFrames
            } else if (holdLeft > 0) {
                holdLeft -= (end - start)
            } else {
                open = false
            }

            if (!open) {
                for (i in start until end) {
                    out[i * ch] = 0f
                    out[i * ch + 1] = 0f
                }
            }
        }
        return out
    }

    /**
     * G8: де-эссер. Простое подавление сибилянтов (5–9 кГц):
     * если энергия в полосе выше thresholdDb, применяем gain reductionDb к этой полосе.
     * Реализация: high-pass 5 кГц → огибающая → gain на полосе → вычитаем из сигнала.
     */
    @JvmStatic
    fun deEss(pcm: FloatArray, sampleRate: Int, thresholdDb: Float, reductionDb: Float): FloatArray {
        val ch = 2
        val frames = pcm.size / ch
        if (frames == 0) return pcm

        // Полосовой фильтр 5–9 кГц (грубо: high-pass 5 кГц, затем low-pass 9 кГц)
        val hp = highPass(pcm, sampleRate, 5000f, true, true)
        // low-pass через инверсию high-pass? проще — простой one-pole low-pass на 9 кГц
        val band = lowPass(hp, sampleRate, 9000f, true, true)

        // Огибающая (RMS по окну 5 мс)
        val window = max(1, sampleRate / 200)
        val threshold = 10.0.pow(thresholdDb / 20.0).toFloat()
        val reduction = 10.0.pow(-abs(reductionDb) / 20.0).toFloat()

        val out = pcm.copyOf()
        for (start in 0 until frames step window) {
            val end = min(start + window, frames)
            var sum = 0.0
            var count = 0
            for (i in start until end) {
                val l = band[i * ch]
                val r = band[i * ch + 1]
                sum += l * l + r * r
                count += 2
            }
            val rms = if (count > 0) sqrt(sum / count).toFloat() else 0f
            if (rms > threshold) {
                val g = reduction
                for (i in start until end) {
                    out[i * ch] -= band[i * ch] * (1f - g)
                    out[i * ch + 1] -= band[i * ch + 1] * (1f - g)
                }
            }
        }
        return out
    }

    /** One-pole low-pass (для де-эссера). */
    private fun lowPass(
        pcm: FloatArray,
        sampleRate: Int,
        cutoffHz: Float,
        applyL: Boolean,
        applyR: Boolean,
    ): FloatArray {
        val ch = 2
        val frames = pcm.size / ch
        val out = pcm.copyOf()
        val dt = 1.0 / sampleRate
        val rc = 1.0 / (2 * Math.PI * cutoffHz)
        val alpha = dt / (rc + dt)

        if (applyL) {
            var y = 0.0
            for (i in 0 until frames) {
                y += alpha * (out[i * ch] - y)
                out[i * ch] = y.toFloat()
            }
        }
        if (applyR) {
            var y = 0.0
            for (i in 0 until frames) {
                y += alpha * (out[i * ch + 1] - y)
                out[i * ch + 1] = y.toFloat()
            }
        }
        return out
    }

    /**
     * G9: компрессор. thresholdDb — порог (например, -18 dB), ratio — степень сжатия (например, 3.0),
     * attackMs/releaseMs — время атаки/восстановления, makeupDb — компенсация gain.
     */
    @JvmStatic
    fun compress(
        pcm: FloatArray,
        sampleRate: Int,
        thresholdDb: Float,
        ratio: Float,
        attackMs: Float,
        releaseMs: Float,
        makeupDb: Float,
    ): FloatArray {
        val ch = 2
        val frames = pcm.size / ch
        if (frames == 0) return pcm

        val out = pcm.copyOf()
        val threshold = 10.0.pow(thresholdDb / 20.0).toFloat()
        val makeup = 10.0.pow(makeupDb / 20.0).toFloat()
        val attack = exp(-1.0 / (sampleRate * attackMs / 1000.0)).toFloat()
        val release = exp(-1.0 / (sampleRate * releaseMs / 1000.0)).toFloat()

        var env = 0f
        for (i in 0 until frames) {
            val l = pcm[i * ch]
            val r = pcm[i * ch + 1]
            val peak = max(abs(l), abs(r))
            env = if (peak > env) {
                attack * env + (1 - attack) * peak
            } else {
                release * env + (1 - release) * peak
            }

            var gain = 1f
            if (env > threshold) {
                val over = env / threshold // >1
                gain = over.toDouble().pow(1.0 / ratio - 1.0).toFloat()
            }
            out[i * ch] = l * gain * makeup
            out[i * ch + 1] = r * gain * makeup
        }
        return out
    }
}

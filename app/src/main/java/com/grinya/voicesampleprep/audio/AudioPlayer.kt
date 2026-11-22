package com.grinya.voicesampleprep.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * F14: две системы позиционирования:
 *  - playStartFrame/playStartTimeMs/streamStartFrame — «ручной» расчёт по времени;
 *  - lastTimestampFrame/lastTimestampNanos/hasValidTimestamp — по AudioTrack.getTimestamp.
 *  Приоритет: timestamp (если доступен), иначе — ручной.
 */
class AudioPlayer {

    interface Listener {
        fun onPosition(positionMs: Int)

        fun onEnded()

        fun onStateChanged(playing: Boolean)
    }

    private var track: AudioTrack? = null
    private var pcm: FloatArray? = null
    private var totalFrames: Int = 0

    @Volatile private var playing: Boolean = false
    @Volatile private var currentFrame: Int = 0
    private var endFrame: Int = 0

    private var playerThread: Thread? = null
    private val ui = Handler(Looper.getMainLooper())
    private val positionTick: Runnable
    private var listener: Listener? = null

    private var playStartTimeMs: Long = 0
    private var playStartFrame: Int = 0

    private var lastTimestampNanos: Long = 0
    private var lastTimestampFrame: Long = 0
    private var hasValidTimestamp: Boolean = false
    private var streamStartFrame: Int = 0

    private var playLeft: Boolean = true
    private var playRight: Boolean = true
    private var playGeneration: Int = 0

    init {
        positionTick = object : Runnable {
            override fun run() {
                val l = listener
                if (l != null && playing) {
                    var frameToReport: Int

                    if (hasValidTimestamp) {
                        val now = System.nanoTime()
                        val elapsedNanos = now - lastTimestampNanos
                        val framesAdvanced = (elapsedNanos * RATE) / 1_000_000_000L

                        val advanced = lastTimestampFrame + framesAdvanced
                        frameToReport = streamStartFrame + advanced.toInt()
                    } else {
                        val elapsedMs = (System.currentTimeMillis() - playStartTimeMs).toInt()
                        frameToReport = playStartFrame + (elapsedMs * RATE / 1000)
                    }

                    if (frameToReport > endFrame) frameToReport = endFrame
                    if (frameToReport < 0) frameToReport = 0

                    currentFrame = frameToReport
                    l.onPosition(currentFrame * 1000 / RATE)
                    ui.postDelayed(this, 16)
                }
            }
        }
    }

    fun setListener(l: Listener?) {
        this.listener = l
    }

    /** F1: не вызываем pause() — он пересчитывает currentFrame от старых playStart*. */
    fun setPcm(pcm: FloatArray?) {
        playing = false
        playGeneration++
        ui.removeCallbacks(positionTick)

        this.pcm = pcm
        this.totalFrames = if (pcm == null) 0 else pcm.size / CHANNELS
        this.currentFrame = 0
        this.endFrame = totalFrames
        this.playStartFrame = 0
        this.playStartTimeMs = System.currentTimeMillis()
        this.streamStartFrame = 0
        this.lastTimestampFrame = 0
        this.lastTimestampNanos = 0
        this.hasValidTimestamp = false
    }

    fun setChannelMask(left: Boolean, right: Boolean) {
        this.playLeft = left
        this.playRight = right
    }

    fun setPlayRange(startFrame: Int, endFrame: Int) {
        this.endFrame = minOf(totalFrames, endFrame)
        if (this.endFrame <= 0) this.endFrame = totalFrames
        if (this.currentFrame < startFrame || this.currentFrame >= this.endFrame) {
            this.currentFrame = maxOf(0, startFrame)
        }
    }

    fun getCurrentFrame(): Int = currentFrame

    fun getTotalFrames(): Int = totalFrames

    fun isPlaying(): Boolean = playing

    fun seekToFrame(frame: Int) {
        Log.d("PLAYER", "seekToFrame($frame) called. wasPlaying=$playing currentFrame=$currentFrame")
        val wasPlaying = playing
        pause()
        currentFrame = maxOf(0, minOf(endFrame, frame))
        playStartFrame = currentFrame
        playStartTimeMs = System.currentTimeMillis()
        notifyPosition()
        if (wasPlaying) play()
    }

    fun play() {
        Log.d(
            "PLAYER", "play() called. currentFrame=$currentFrame endFrame=$endFrame " +
                "totalFrames=$totalFrames"
        )

        val src = pcm ?: return
        if (totalFrames == 0) return
        if (playing) return

        // F13: не блокируем UI-поток join(500); если старый поток жив — просто увеличиваем поколение,
        // он сам завершится и увидит myGeneration != playGeneration.

        val myGeneration = ++playGeneration

        if (currentFrame >= totalFrames) currentFrame = 0
        if (endFrame > 0 && currentFrame >= endFrame) currentFrame = playStartFrame

        playStartFrame = currentFrame
        streamStartFrame = currentFrame
        playStartTimeMs = System.currentTimeMillis()
        hasValidTimestamp = false
        lastTimestampFrame = 0
        lastTimestampNanos = 0
        playing = true

        val framesToPlay = endFrame - playStartFrame
        val durationSec = framesToPlay.toDouble() / RATE

        val thread = Thread(Runnable {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()

            if (durationSec < 10.0) {
                // --- КОРОТКИЙ ФАЙЛ ---
                val numFrames = framesToPlay
                val data = FloatArray(numFrames * CHANNELS)
                System.arraycopy(src, playStartFrame * CHANNELS, data, 0, numFrames * CHANNELS)

                for (i in data.indices step 2) {
                    if (!playLeft) data[i] = 0f
                    if (!playRight) data[i + 1] = 0f
                }

                val t = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(numFrames * CHANNELS * 4)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track = t
                val bb = ByteBuffer.allocateDirect(data.size * 4)
                bb.order(ByteOrder.nativeOrder())
                bb.asFloatBuffer().put(data)
                t.write(bb, data.size * 4, AudioTrack.WRITE_BLOCKING)
                t.play()

                val startTime = System.currentTimeMillis()
                val durationMs = (durationSec * 1000).toLong()

                while (playing && myGeneration == playGeneration) {
                    try {
                        Thread.sleep(20)
                    } catch (ignored: InterruptedException) {
                    }
                    val elapsed = System.currentTimeMillis() - startTime
                    if (elapsed >= durationMs) break
                }

                try {
                    t.stop()
                } catch (ignored: Exception) {
                }
                try {
                    t.release()
                } catch (ignored: Exception) {
                }
                if (track === t) track = null

                if (myGeneration == playGeneration) {
                    playing = false
                    if (currentFrame < endFrame) currentFrame = endFrame
                }

                ui.post {
                    if (myGeneration != playGeneration) return@post
                    listener?.let {
                        it.onStateChanged(false)
                        it.onPosition(currentFrame * 1000 / RATE)
                        if (currentFrame >= endFrame) it.onEnded()
                    }
                }
            } else {
                // --- ДЛИННЫЙ ФАЙЛ ---
                val minBuf = AudioTrack.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
                )
                val bufSize = maxOf(minBuf, 65536)

                val t = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track = t
                t.play()

                Thread(Runnable {
                    val ts = AudioTimestamp()
                    while (playing && myGeneration == playGeneration) {
                        if (t.getTimestamp(ts)) {
                            lastTimestampNanos = ts.nanoTime
                            lastTimestampFrame = ts.framePosition
                            hasValidTimestamp = true
                        }
                        try {
                            Thread.sleep(100)
                        } catch (ignored: InterruptedException) {
                        }
                    }
                }, "AudioSync").start()

                var chunk = bufSize / 4 / CHANNELS
                if (chunk <= 0) chunk = 1024
                var frame = playStartFrame

                while (frame < endFrame && playing && myGeneration == playGeneration) {
                    val n = minOf(chunk, endFrame - frame)
                    val block = FloatArray(n * CHANNELS)
                    System.arraycopy(src, frame * CHANNELS, block, 0, n * CHANNELS)

                    for (i in block.indices step 2) {
                        if (!playLeft) block[i] = 0f
                        if (!playRight) block[i + 1] = 0f
                    }
                    var written = 0
                    val totalToWrite = block.size
                    while (written < totalToWrite && playing && myGeneration == playGeneration) {
                        val w = t.write(block, written, totalToWrite - written, AudioTrack.WRITE_BLOCKING)
                        if (w < 0) {
                            Log.e("PLAYER", "write error=$w")
                            break
                        }
                        written += w
                    }
                    frame += n
                }

                hasValidTimestamp = false
                try {
                    Thread.sleep(100)
                } catch (ignored: InterruptedException) {
                }
                try {
                    t.stop()
                } catch (ignored: Exception) {
                }
                try {
                    t.release()
                } catch (ignored: Exception) {
                }
                if (track === t) track = null

                if (myGeneration == playGeneration) {
                    playing = false
                    currentFrame = minOf(currentFrame, endFrame)
                }

                ui.post {
                    if (myGeneration != playGeneration) return@post
                    listener?.let {
                        it.onStateChanged(false)
                        it.onPosition(currentFrame * 1000 / RATE)
                        if (currentFrame >= endFrame) it.onEnded()
                    }
                }
            }
        }, "AudioPlayer")

        playerThread = thread
        thread.start()
        ui.post(positionTick)
        listener?.onStateChanged(true)
    }

    fun pause() {
        Log.d("PLAYER", "pause() called. playing=$playing currentFrame=$currentFrame")
        if (!playing) return
        playing = false
        playGeneration++
        ui.removeCallbacks(positionTick)

        val elapsedMs = (System.currentTimeMillis() - playStartTimeMs).toInt()
        currentFrame = minOf(playStartFrame + (elapsedMs * RATE / 1000), endFrame)

        listener?.onStateChanged(false)
    }

    fun stop() {
        pause()
        currentFrame = 0
        notifyPosition()
    }

    private fun notifyPosition() {
        listener?.onPosition(currentFrame * 1000 / RATE)
    }

    fun release() {
        playing = false
        playGeneration++
        ui.removeCallbacks(positionTick)
        this.listener = null
        this.pcm = null
    }

    companion object {
        private const val RATE = 48000
        private const val CHANNELS = 2
    }
}

package com.grinya.voicesampleprep.edit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.grinya.voicesampleprep.model.AudioProject
import com.grinya.voicesampleprep.model.AudioClip
import com.grinya.voicesampleprep.model.ProjectTrack
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MultitrackWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onTrackSelected(track: ProjectTrack)
        fun onTrackLongPressed(track: ProjectTrack)
    }

    private var listener: Listener? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    // --- Данные ---
    private var project: AudioProject? = null
    private var activeTrack: ProjectTrack? = null

    // --- Просмотр ---
    private var viewStartFrame = 0.0
    private var viewFramesPerPixel = 1.0
    private var paddingPx = (8 * resources.displayMetrics.density).toInt()
    private var headerWidthPx = (80 * resources.displayMetrics.density).toInt()

    // --- Жесты ---
    private var gestureDetector: GestureDetector? = null
    private var scaleDetector: ScaleGestureDetector? = null
    private var lastTouchX = 0f

    // --- Краски ---
    private val pHeaderBg = Paint().apply { color = 0xFF1A1A1E.toInt(); style = Paint.Style.FILL }
    private val pHeaderText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 12f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val pActiveBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val pWaveFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        style = Paint.Style.FILL
        alpha = 180 // Полупрозрачная заливка
    }
    private val pWaveStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val pGrid = Paint().apply { color = 0xFF1F1F22.toInt(); strokeWidth = 1f }

    init {
        setBackgroundColor(0xFF0E0E10.toInt())

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (project == null) return true
                val oldFpp = viewFramesPerPixel
                var newFpp = oldFpp / detector.scaleFactor
                val minFpp = 1.0 / 8.0
                val maxFpp = max(1, project?.totalFrames() ?: 1).toDouble()
                if (newFpp < minFpp) newFpp = minFpp
                if (newFpp > maxFpp) newFpp = maxFpp

                val fx = detector.focusX - headerWidthPx
                val focusFrame = viewStartFrame + fx * oldFpp
                viewStartFrame = focusFrame - fx * newFpp
                viewFramesPerPixel = newFpp
                clampView()
                invalidate()
                return true
            }
        })

        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                activeTrack?.let { listener?.onTrackLongPressed(it) }
            }
        })
    }

    fun setProject(p: AudioProject?) {
        project = p
        activeTrack = p?.tracks?.firstOrNull()
        viewStartFrame = 0.0
        viewFramesPerPixel = 1.0
        invalidate()
    }

    private fun clampView() {
        val p = project ?: return
        val totalFrames = p.totalFrames()
        if (totalFrames <= 0) {
            viewStartFrame = 0.0
            return
        }
        val visibleWidth = width - headerWidthPx - 2 * paddingPx
        if (visibleWidth <= 0) return
        val visibleFrames = visibleWidth * viewFramesPerPixel
        val maxStart = max(0.0, totalFrames - visibleFrames)
        if (viewStartFrame < 0) viewStartFrame = 0.0
        if (viewStartFrame > maxStart) viewStartFrame = maxStart
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        clampView()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector?.onTouchEvent(e)
        gestureDetector?.onTouchEvent(e)
        if (scaleDetector?.isInProgress == true) return true

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = e.x
                if (e.x < headerWidthPx) return true

                val p = project ?: return true
                val lanes = p.laneCount()
                val laneHeight = height.toFloat() / lanes
                val laneIndex = (e.y / laneHeight).toInt()

                if (laneIndex in 0 until lanes) {
                    for (track in p.tracks) {
                        if (track.laneIndex == laneIndex) {
                            val trackStartX = headerWidthPx + paddingPx + ((track.startOffsetFrames - viewStartFrame) / viewFramesPerPixel).toFloat()
                            val trackEndX = trackStartX + track.clip.frameCount() / viewFramesPerPixel
                            if (e.x >= trackStartX && e.x <= trackEndX) {
                                activeTrack = track
                                invalidate()
                                listener?.onTrackSelected(track)
                                break
                            }
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = lastTouchX - e.x
                viewStartFrame += dx * viewFramesPerPixel
                lastTouchX = e.x
                clampView()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        canvas.drawColor(0xFF0E0E10.toInt())

        val p = project ?: return
        if (p.tracks.isEmpty()) return

        val lanes = p.laneCount()
        val laneHeight = h.toFloat() / lanes

        // Заголовки
        canvas.drawRect(0f, 0f, headerWidthPx.toFloat(), h.toFloat(), pHeaderBg)
        for (track in p.tracks) {
            val laneTop = track.laneIndex * laneHeight
            val textY = laneTop + laneHeight / 2 + pHeaderText.textSize / 3
            canvas.drawText(track.displayName, 8f, textY, pHeaderText)
        }

        // Сетка
        drawGrid(canvas, w, h, p)

        // Волны
        for (track in p.tracks) {
            val laneTop = track.laneIndex * laneHeight
            val laneCenterY = laneTop + laneHeight / 2
            val halfH = laneHeight / 2 - 4

            if (track === activeTrack) {
                canvas.drawRect(headerWidthPx.toFloat(), laneTop, (w - paddingPx).toFloat(),
                    laneTop + laneHeight, pActiveBorder)
            }

            val trackStartX = headerWidthPx + paddingPx + ((track.startOffsetFrames - viewStartFrame) / viewFramesPerPixel).toFloat()

            // Рисуем волну
            drawWaveform(canvas, track.clip, trackStartX, laneCenterY, halfH, w)
        }
    }

    private fun drawGrid(canvas: Canvas, w: Int, h: Int, p: AudioProject) {
        val sr = p.sampleRate
        if (sr <= 0) return
        val stepSec = 1.0
        val stepFrames = stepSec * sr
        val total = p.totalFrames()
        var f = 0.0
        while (f <= total) {
            val x = headerWidthPx + paddingPx + ((f - viewStartFrame) / viewFramesPerPixel).toFloat()
            if (x >= headerWidthPx && x <= w - paddingPx) {
                canvas.drawLine(x, 0f, x, h.toFloat(), pGrid)
            }
            f += stepFrames
        }
    }

    private fun drawWaveform(canvas: Canvas, clip: AudioClip, startX: Float, centerY: Float, halfH: Float, viewWidth: Int) {
        val pcm = clip.pcm
        val totalFrames = pcm.size / 2
        if (totalFrames == 0) return

        val visibleWidth = viewWidth - headerWidthPx - paddingPx
        if (visibleWidth <= 0) return

        // Находим пик для нормализации (чтобы тихие треки были видны)
        var maxAmp = 0.001f
        for (i in pcm.indices) {
            val v = abs(pcm[i])
            if (v > maxAmp) maxAmp = v
        }
        val gain = (1.0f / maxAmp).coerceIn(1.0f, 3.0f) // Усиление до 3x

        val path = Path()
        path.reset()
        var started = false

        // Рисуем верхнюю огибающую (max)
        for (x in 0 until visibleWidth) {
            val screenX = startX + x
            if (screenX < headerWidthPx || screenX > viewWidth - paddingPx) continue

            val framePos = viewStartFrame + (screenX - headerWidthPx - paddingPx) * viewFramesPerPixel
            val f0 = framePos.toInt()
            if (f0 < 0 || f0 >= totalFrames) continue

            var maxVal = 0f
            val f1 = min(f0 + viewFramesPerPixel.toInt().coerceAtLeast(1), totalFrames)
            for (f in f0 until f1) {
                val l = pcm[f * 2]
                val r = pcm[f * 2 + 1]
                val mix = (l + r) * 0.5f
                if (mix > maxVal) maxVal = mix
            }
            
            val y = centerY - (maxVal * gain * halfH).coerceIn(-halfH, halfH)
            if (!started) { path.moveTo(screenX, y); started = true }
            else path.lineTo(screenX, y)
        }

        // Рисуем нижнюю огибающую (min) в обратном порядке
        for (x in visibleWidth - 1 downTo 0) {
            val screenX = startX + x
            if (screenX < headerWidthPx || screenX > viewWidth - paddingPx) continue

            val framePos = viewStartFrame + (screenX - headerWidthPx - paddingPx) * viewFramesPerPixel
            val f0 = framePos.toInt()
            if (f0 < 0 || f0 >= totalFrames) continue

            var minVal = 0f
            val f1 = min(f0 + viewFramesPerPixel.toInt().coerceAtLeast(1), totalFrames)
            for (f in f0 until f1) {
                val l = pcm[f * 2]
                val r = pcm[f * 2 + 1]
                val mix = (l + r) * 0.5f
                if (mix < minVal) minVal = mix
            }
            
            val y = centerY - (minVal * gain * halfH).coerceIn(-halfH, halfH)
            path.lineTo(screenX, y)
        }
        path.close()

        canvas.drawPath(path, pWaveFill)
        canvas.drawPath(path, pWaveStroke)
    }
}
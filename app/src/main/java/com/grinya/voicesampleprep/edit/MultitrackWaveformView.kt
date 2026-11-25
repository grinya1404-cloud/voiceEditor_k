package com.grinya.voicesampleprep.edit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.GestureDetector
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
        fun onTrackLongPressed(track: ProjectTrack) // <-- Новый метод
    }

    private var listener: Listener? = null

    fun setListener(l: Listener?) {
        listener = l
    }

    private var project: AudioProject? = null
    private var activeTrack: ProjectTrack? = null

    // Параметры просмотра
    private var viewStartFrame = 0.0
    private var viewFramesPerPixel = 1.0
    private var paddingPx = (8 * resources.displayMetrics.density).toInt()
    private var headerWidthPx = (80 * resources.displayMetrics.density).toInt()

    // Жесты
    private var gestureDetector: GestureDetector? = null
    private var scaleDetector: ScaleGestureDetector? = null
    private var lastTouchX = 0f

    // Краски
    private val pBg = Paint().apply { color = 0xFF0E0E10.toInt() }
    private val pTrackBg = Paint().apply { color = 0x33FFFFFF }
    private val pHeaderBg = Paint().apply {
        color = 0xFF1A1A1E.toInt()
        style = Paint.Style.FILL
    }
    private val pHeaderText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 12f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val pWave = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        style = Paint.Style.FILL
    }
    private val pWaveStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val pActiveBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val pGrid = Paint().apply {
        color = 0xFF1F1F22.toInt()
        strokeWidth = 1f
    }

    private val trackPeaks = mutableMapOf<ProjectTrack, FloatArray>()

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
                    val track = activeTrack
                    if (track != null) {
                        // Вызываем коллбэк долгого тапа (нужно добавить в Listener)
                        listener?.onTrackLongPressed(track)
                    }
                }
        })
    }

    fun setProject(p: AudioProject?) {
        project = p
        activeTrack = p?.tracks?.firstOrNull()
        trackPeaks.clear()
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
        gestureDetector?.onTouchEvent(e) // <-- Добавить эту строку
        if (scaleDetector?.isInProgress == true) return true

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = e.x
                if (e.x < headerWidthPx) return true // Тап по заголовку

                // Выбор активного трека
                val p = project ?: return true
                val lanes = p.laneCount()
                val waveH = height
                val laneHeight = waveH.toFloat() / lanes
                val laneIndex = (e.y / laneHeight).toInt()

                if (laneIndex in 0 until lanes) {
                    // ВОТ ЭТОТ ЦИКЛ ДОЛЖЕН БЫТЬ:
                    for (track in p.tracks) {
                        if (track.laneIndex == laneIndex) {
                            val trackStartX = headerWidthPx + ((track.startOffsetFrames - viewStartFrame) / viewFramesPerPixel).toInt()
                            val trackEndX = trackStartX + track.clip.frameCount() / viewFramesPerPixel

                            if (e.x >= trackStartX && e.x <= trackEndX) {
                                activeTrack = track
                                invalidate()
                                listener?.onTrackSelected(track) // Сообщаем Activity, что трек выбран
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
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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
        val visibleWaveWidth = w - headerWidthPx - 2 * paddingPx

        // Заголовки
        canvas.drawRect(0f, 0f, headerWidthPx.toFloat(), h.toFloat(), pHeaderBg)

        for (track in p.tracks) {
            val laneTop = track.laneIndex * laneHeight
            val textY = laneTop + laneHeight / 2 + pHeaderText.textSize / 3
            canvas.drawText(track.displayName, 8f, textY, pHeaderText)
        }

        // Сетка (вертикальные линии)
        drawGrid(canvas, w, h, p)

        // Волны
        val leftClip = max(0, viewStartFrame.toInt())
        val rightClip = (viewStartFrame + visibleWaveWidth * viewFramesPerPixel).toInt()

        for ((index, track) in p.tracks.withIndex()) {
            val laneTop = track.laneIndex * laneHeight
            val laneCenterY = laneTop + laneHeight / 2

            if (track === activeTrack) {
                canvas.drawRect(headerWidthPx.toFloat(), laneTop, (w - paddingPx).toFloat(),
                    laneTop + laneHeight, pActiveBorder)
            }

            val trackStartX = headerWidthPx + paddingPx + ((track.startOffsetFrames - viewStartFrame) / viewFramesPerPixel).toFloat()
            val trackEndX = trackStartX + track.clip.frameCount() / viewFramesPerPixel

            val drawStartX = max((headerWidthPx + paddingPx).toFloat(), trackStartX)
            val drawEndX = min((w - paddingPx).toFloat(), trackEndX.toFloat())

            if (drawStartX < drawEndX) {
                val peaks = trackPeaks.getOrPut(track) { buildPeaks(track.clip, visibleWaveWidth) }
                val halfH = laneHeight / 2 - 4

                canvas.save()
                canvas.translate(trackStartX - (headerWidthPx + paddingPx), 0f)
                drawWaveform(canvas, peaks, laneCenterY, halfH)
                canvas.restore()
            }
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

    private fun buildPeaks(clip: AudioClip, width: Int): FloatArray {
        val peaks = FloatArray(width)
        val pcm = clip.pcm
        val totalFrames = pcm.size / 2
        if (totalFrames == 0 || width <= 0) return peaks

        // Простой расчет пиков для видимой области
        val startFrame = max(0, viewStartFrame.toInt())
        val framesPerPx = viewFramesPerPixel

        for (x in 0 until width) {
            val f0 = (startFrame + x * framesPerPx).toInt()
            val f1 = (startFrame + (x + 1) * framesPerPx).toInt()
            var maxVal = 0f
            for (f in f0 until min(f1, totalFrames)) {
                val l = abs(pcm[f * 2])
                val r = abs(pcm[f * 2 + 1])
                if (l > maxVal) maxVal = l
                if (r > maxVal) maxVal = r
            }
            peaks[x] = maxVal
        }
        return peaks
    }

    private fun drawWaveform(canvas: Canvas, peaks: FloatArray, centerY: Float, halfH: Float) {
        val path = android.graphics.Path()
        path.reset()
        for (x in peaks.indices) {
            val y = centerY - peaks[x] * halfH
            if (x == 0) path.moveTo(x.toFloat(), y)
            else path.lineTo(x.toFloat(), y)
        }
        for (x in peaks.indices.reversed()) {
            val y = centerY + peaks[x] * halfH
            path.lineTo(x.toFloat(), y)
        }
        path.close()
        canvas.drawPath(path, pWave)
        canvas.drawPath(path, pWaveStroke)
    }
}

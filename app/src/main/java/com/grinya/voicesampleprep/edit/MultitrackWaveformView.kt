package com.grinya.voicesampleprep.edit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.grinya.voicesampleprep.model.AudioProject
import com.grinya.voicesampleprep.model.ProjectManager
import com.grinya.voicesampleprep.model.ProjectTrack
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
    fun setListener(l: Listener?) { listener = l }

    private var project: AudioProject? = null
    private var activeTrack: ProjectTrack? = null

    // Просмотр
    private var viewStartFrame = 0.0
    private var viewFramesPerPixel = 1.0
    private var paddingPx = (8 * resources.displayMetrics.density).toInt()
    private var isEditMode = false

    // Жесты и перетаскивание
    private var gestureDetector: GestureDetector? = null
    private var scaleDetector: ScaleGestureDetector? = null
    private var lastTouchX = 0f
    private var draggingTrack: ProjectTrack? = null
    private var dragStartOffset = 0
    private var dragStartTouchX = 0f

    // Краски
    private val pActiveBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val pWaveFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFB300.toInt()
        style = Paint.Style.FILL
    }
    private val pTrackName = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt() // Полупрозрачный белый
        textSize = 14f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val pGrid = Paint().apply { color = 0xFF1F1F22.toInt(); strokeWidth = 1f }

    init {
        setBackgroundColor(0xFF0E0E10.toInt())

        // 1. Зум
        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (project == null) return true
                val oldFpp = viewFramesPerPixel
                var newFpp = oldFpp / detector.scaleFactor
                val minFpp = 1.0 / 8.0
                val maxFpp = max(1, project?.totalFrames() ?: 1).toDouble()
                if (newFpp < minFpp) newFpp = minFpp
                if (newFpp > maxFpp) newFpp = maxFpp

                val fx = detector.focusX - paddingPx
                val focusFrame = viewStartFrame + fx * oldFpp
                viewStartFrame = focusFrame - fx * newFpp
                viewFramesPerPixel = newFpp
                clampView()
                invalidate()
                return true
            }
        })

        // 2. Тапы (двойной тап для зума, долгий для меню/драга)
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoomToFit()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val track = findTrackAt(e.x, e.y) ?: return
                activeTrack = track
                invalidate()

                if (isEditMode) {
                    // Режим редактирования -> захватываем для драга
                    draggingTrack = track
                    dragStartOffset = track.startOffsetFrames
                    dragStartTouchX = e.x
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                } else {
                    // Режим просмотра -> меню
                    listener?.onTrackLongPressed(track)
                }
            }
        })
    }

    fun setProject(p: AudioProject?) {
        project = p
        activeTrack = p?.tracks?.firstOrNull()
        viewStartFrame = 0.0
        viewFramesPerPixel = 1.0
        post { zoomToFit() } // Сжимаем, когда View узнает свои размеры
        invalidate()
    }

    fun setEditMode(enabled: Boolean) {
        isEditMode = enabled
        invalidate()
    }

    /** Сжимает проект так, чтобы он весь влез на экран */
    fun zoomToFit() {
        val p = project ?: return
        val totalFrames = p.totalFrames()
        if (totalFrames <= 0) return
        val visibleWidth = width - 2 * paddingPx
        if (visibleWidth <= 0) return

        viewFramesPerPixel = totalFrames.toDouble() / visibleWidth
        viewStartFrame = 0.0
        invalidate()
    }

    private fun clampView() {
        val p = project ?: return
        val totalFrames = p.totalFrames()
        if (totalFrames <= 0) { viewStartFrame = 0.0; return }
        
        val visibleWidth = width - 2 * paddingPx
        if (visibleWidth <= 0) return
        val visibleFrames = visibleWidth * viewFramesPerPixel
        val maxStart = max(0.0, totalFrames - visibleFrames)
        
        if (viewStartFrame < 0) viewStartFrame = 0.0
        if (viewStartFrame > maxStart) viewStartFrame = maxStart
    }

    /** Находит трек под координатами (X, Y) */
    private fun findTrackAt(x: Float, y: Float): ProjectTrack? {
        val p = project ?: return null
        val lanes = p.laneCount()
        val laneHeight = height.toFloat() / lanes
        val laneIndex = (y / laneHeight).toInt()

        if (laneIndex in 0 until lanes) {
            for (track in p.tracks) {
                if (track.laneIndex == laneIndex) {
                    val trackStartX = paddingPx + ((track.startOffsetFrames - viewStartFrame) / viewFramesPerPixel).toFloat()
                    val trackWidthPx = track.clip.frameCount() / viewFramesPerPixel
                    val trackEndX = trackStartX + trackWidthPx

                    if (x >= trackStartX && x <= trackEndX) {
                        return track // Нашли!
                    }
                }
            }
        }
        return null
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (project != null && viewFramesPerPixel == 1.0) zoomToFit()
        clampView()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector?.onTouchEvent(e)
        gestureDetector?.onTouchEvent(e)
        if (scaleDetector?.isInProgress == true) return true

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = e.x
                // Выделяем трек при касании (чтобы была рамка)
                val track = findTrackAt(e.x, e.y)
                if (track != null) {
                    activeTrack = track
                    invalidate()
                    listener?.onTrackSelected(track)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dt = draggingTrack
                if (dt != null) {
                    // Двигаем трек
                    val dx = e.x - dragStartTouchX
                    val deltaFrames = (dx * viewFramesPerPixel).toInt()
                    dt.startOffsetFrames = (dragStartOffset + deltaFrames).coerceAtLeast(0)
                    invalidate()
                } else {
                    // Скроллим проект
                    val dx = lastTouchX - e.x
                    viewStartFrame += dx * viewFramesPerPixel
                    lastTouchX = e.x
                    clampView()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingTrack != null) {
                    project?.let { ProjectManager.saveProject(it) }
                    draggingTrack = null
                }
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

        // Сетка
        drawGrid(canvas, w, h, p)

        // Треки
        for (track in p.tracks) {
            val laneTop = track.laneIndex * laneHeight
            val laneCenterY = laneTop + laneHeight / 2
            val halfH = laneHeight / 2 - 6

            // Рамка активного трека
            if (track === activeTrack) {
                canvas.drawRect(0f, laneTop, w.toFloat(), laneTop + laneHeight, pActiveBorder)
            }

            // Волна
            val trackStartX = paddingPx + ((track.startOffsetFrames - viewStartFrame) / viewFramesPerPixel).toFloat()
            drawWaveformBlocks(canvas, track, trackStartX, laneCenterY, halfH, w)

            // Название трека (сверху слева от волны)
            canvas.drawText(track.displayName, paddingPx + 8f, laneTop + 20f * resources.displayMetrics.density, pTrackName)
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
            val x = paddingPx + ((f - viewStartFrame) / viewFramesPerPixel).toFloat()
            if (x >= paddingPx && x <= w - paddingPx) {
                canvas.drawLine(x, 0f, x, h.toFloat(), pGrid)
            }
            f += stepFrames
        }
    }

    private fun drawWaveformBlocks(canvas: Canvas, track: ProjectTrack, startX: Float, centerY: Float, halfH: Float, viewWidth: Int) {
        val pcm = track.clip.pcm
        val totalFrames = pcm.size / 2
        if (totalFrames == 0) return

        val blockWidthPx = 6f
        val blockGapPx = 2f

        var maxAmp = 0.001f
        for (i in pcm.indices) {
            val v = kotlin.math.abs(pcm[i])
            if (v > maxAmp) maxAmp = v
        }
        val gain = (1.0f / maxAmp).coerceIn(1.0f, 4.0f)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFB300.toInt()
            style = Paint.Style.FILL
        }

        val visibleWidth = viewWidth - 2 * paddingPx
        if (visibleWidth <= 0) return

        val stepPx = blockWidthPx + blockGapPx
        val numBlocks = (visibleWidth / stepPx).toInt()
        val startCanvasX = paddingPx

        for (i in 0 until numBlocks) {
            val blockCanvasX = startCanvasX + i * stepPx
            if (blockCanvasX < paddingPx || blockCanvasX > viewWidth - paddingPx) continue

            val localX = blockCanvasX - startX
            val frameStart = (localX * viewFramesPerPixel).toInt()
            val frameEnd = min(((localX + blockWidthPx) * viewFramesPerPixel).toInt(), totalFrames)

            if (frameStart < 0 || frameStart >= totalFrames) continue

            var minVal = 0f
            var maxVal = 0f
            var found = false

            for (f in frameStart until frameEnd) {
                val l = pcm[f * 2]
                val r = pcm[f * 2 + 1]
                val mix = (l + r) * 0.5f
                if (!found) {
                    minVal = mix
                    maxVal = mix
                    found = true
                } else {
                    if (mix < minVal) minVal = mix
                    if (mix > maxVal) maxVal = mix
                }
            }

            if (found) {
                val maxAmpInBlock = max(kotlin.math.abs(minVal), kotlin.math.abs(maxVal))
                val amp = (maxAmpInBlock * gain).coerceIn(0f, 1f)

                val topY = centerY - (amp * halfH)
                val botY = centerY + (amp * halfH)

                canvas.drawRect(blockCanvasX, topY, blockCanvasX + blockWidthPx, botY, paint)
            }
        }
    }
}
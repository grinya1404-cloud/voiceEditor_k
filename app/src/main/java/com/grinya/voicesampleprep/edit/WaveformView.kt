package com.grinya.voicesampleprep.edit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.grinya.voicesampleprep.R
import com.grinya.voicesampleprep.util.TimeFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onSelectionChanged(startFrame: Int, endFrame: Int)
        fun onTap(frame: Int)
        fun onPlayheadDragged(frame: Int)
        fun onMarkerTapped(frame: Int)
        fun onMarkerLongPressed(frame: Int)
        fun onMarkerMoved(oldFrame: Int, newFrame: Int)
        fun onChannelToggled(left: Boolean, right: Boolean)
        fun onIntervalLongPressed(startFrame: Int, endFrame: Int)
        fun onIntervalsModeChanged(enabled: Boolean)
        fun onIntervalTapped(startFrame: Int, endFrame: Int)
    }

    // ---------- поля ----------
    private var pcm: FloatArray? = null
    private var totalFrames = 0
    private var sampleRate = 48000
    private var markers: List<Int>? = null
    private var exportIntervals: List<IntArray>? = null

    private var viewStartFrame = 0.0
    private var viewFramesPerPixel = 1.0
    private var playheadFrame = 0
    private var activePlayFrame = 0

    private var selStart = -1
    private var selEnd = -1
    private var paddingPx = 0

    private var stereoView = false
    private var channelL = true
    private var channelR = true
    private var checkLx = 0f
    private var checkLy = 0f
    private var checkRx = 0f
    private var checkRy = 0f
    private var checkSize = 0

    private var dragMode = DRAG_NONE
    private var dragMarkerOldFrame = -1
    private var dragMarkerFrame = -1
    private var lastTouchX = 0f
    private var downX = 0f
    private var downY = 0f
    private var downSelStart = 0
    private var downSelEnd = 0
    private var longPressActivated = false
    private var downMarkerFrame = -1
    private var zeroCrossingSnap = true

    private var intervalsEditMode = false
    private var intervalPage = 0
    private var intervalPageSize = 6
    private var selectedIntervalIndex = -1
    private var justEnteredIntervalsMode = false // Флаг для первого тапа
    private var lastWaveTop = 0
    private var lastWaveBottom = 0
    private var lastWaveWidth = 0
    private var downIntervalStart = -1
    private var downIntervalEnd = -1

    private var listener: Listener? = null
    private var gestureDetector: GestureDetector? = null
    private var scaleDetector: ScaleGestureDetector? = null

    private var rulerHeightPx = 0
    private var handleHalfPx = 0
    private var markerFlagW = 0
    private var markerFlagH = 0
    private var ampScaleWidthPx = 0

    private var peakMin: FloatArray? = null
    private var peakMax: FloatArray? = null
    private var peakMinR: FloatArray? = null
    private var peakMaxR: FloatArray? = null
    private var peakMaxMono: FloatArray? = null
    private var peakMinMono: FloatArray? = null
    private var peakCacheWidth = 0
    private var peakCacheStart = 0.0
    private var peakCacheFpp = 0.0

    // ---------- Paint ----------
    private val pWave = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pWaveFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pCenter = Paint()
    private val pPlayhead = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pActivePlayhead = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pSel = Paint()
    private val pSelBorder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pMarker = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pMarkerFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pMarkerText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pGrid = Paint()
    private val pRuler = Paint()
    private val pRulerText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pCheck = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pCheckBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pChannelText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pScaleBg = Paint()
    private val pEmpty = Paint()
    private val pIntervalFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pIntervalFillNormal = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pIntervalStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pIntervalText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pCheckboxBgOn = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pCheckboxBgOff = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pCheckboxBorderOn = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pCheckboxBorderOff = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pAmpScale = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pAmpText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pHGrid = Paint()
    private val wavePath = Path()
    private val tmpRect = RectF()

    private val longPressRunnable = Runnable {
        if (downIntervalStart != -1) {
            listener?.onIntervalLongPressed(downIntervalStart, downIntervalEnd)
            longPressActivated = true
            downIntervalStart = -1
            downIntervalEnd = -1
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            return@Runnable
        }
        if (downMarkerFrame != -1) {
            listener?.onMarkerLongPressed(downMarkerFrame)
            longPressActivated = true
            downMarkerFrame = -1
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            return@Runnable
        }
        if (dragMode == DRAG_SCROLL) {
            longPressActivated = true
            dragMode = DRAG_NEW_SELECTION
            val f = xToFrame(downX)
            downSelStart = f
            selStart = f
            selEnd = f
            invalidate()
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    companion object {
        private const val RULER_HEIGHT_DP = 22
        private const val HANDLE_HALF_DP = 10
        private const val MARKER_FLAG_W_DP = 10
        private const val MARKER_FLAG_H_DP = 14
        private const val DRAG_NONE = 0
        private const val DRAG_SCROLL = 1
        private const val DRAG_SEL_START = 2
        private const val DRAG_SEL_END = 3
        private const val DRAG_NEW_SELECTION = 4
        private const val DRAG_PLAYHEAD = 6
        private const val DRAG_MARKER = 7
    }

    init {
        init(context)
    }

    private fun init(c: Context) {
        val density = c.resources.displayMetrics.density
        rulerHeightPx = (RULER_HEIGHT_DP * density).toInt()
        handleHalfPx = (HANDLE_HALF_DP * density).toInt()
        markerFlagW = (MARKER_FLAG_W_DP * density).toInt()
        markerFlagH = (MARKER_FLAG_H_DP * density).toInt()
        checkSize = (32 * density).toInt()
        paddingPx = (25 * density).toInt()

        // Загрузка цветов из ресурсов
        val colorBg = ContextCompat.getColor(c, R.color.wave_bg)
        val colorWave = ContextCompat.getColor(c, R.color.wave_line)
        val colorCenterLine = ContextCompat.getColor(c, R.color.wave_center_line)
        val colorPlayhead = ContextCompat.getColor(c, R.color.wave_playhead)
        val colorActivePlayhead = ContextCompat.getColor(c, R.color.wave_active_playhead)
        val colorSelection = ContextCompat.getColor(c, R.color.wave_selection)
        val colorSelectionBorder = ContextCompat.getColor(c, R.color.wave_selection_border)
        val colorMarker = ContextCompat.getColor(c, R.color.wave_marker)
        val colorGrid = ContextCompat.getColor(c, R.color.wave_grid)
        val colorRuler = ContextCompat.getColor(c, R.color.wave_ruler)
        val colorRulerText = ContextCompat.getColor(c, R.color.wave_ruler_text)
        val colorCheck = ContextCompat.getColor(c, R.color.wave_check)

        pWave.color = colorWave
        pWave.strokeWidth = 1.5f
        pWave.style = Paint.Style.STROKE

        pWaveFill.color = 0x55FFFFFF
        pWaveFill.style = Paint.Style.FILL

        pCenter.color = colorCenterLine
        pCenter.strokeWidth = 1f

        pPlayhead.color = colorPlayhead
        pPlayhead.strokeWidth = 2f * density

        pActivePlayhead.color = colorActivePlayhead
        pActivePlayhead.strokeWidth = 3f * density

        pSel.color = colorSelection
        pSel.style = Paint.Style.FILL

        pSelBorder.color = colorSelectionBorder
        pSelBorder.strokeWidth = 1.5f * density
        pSelBorder.style = Paint.Style.STROKE

        pMarker.color = colorMarker
        pMarker.strokeWidth = 2f * density

        pMarkerFill.color = colorMarker
        pMarkerFill.style = Paint.Style.FILL

        pMarkerText.color = 0xFF0E0E10.toInt()
        pMarkerText.textSize = 9f * density
        pMarkerText.typeface = android.graphics.Typeface.DEFAULT_BOLD

        pGrid.color = colorGrid
        pGrid.strokeWidth = 1f

        pEmpty.color = 0xAA000000.toInt()
        pEmpty.style = Paint.Style.FILL

        pIntervalFill.color = 0x884FC3F7.toInt()
        pIntervalFill.style = Paint.Style.FILL

        pIntervalFillNormal.color = 0x884FC3F7.toInt()
        pIntervalFillNormal.style = Paint.Style.FILL

        pIntervalStroke.color = 0xFFFFB300.toInt()
        pIntervalStroke.strokeWidth = 2f * density
        pIntervalStroke.style = Paint.Style.STROKE

        pIntervalText.color = 0xFF0E0E10.toInt()
        pIntervalText.textSize = 11f * density

        pCheckboxBgOn.color = 0xAAFFB300.toInt()
        pCheckboxBgOn.style = Paint.Style.FILL

        pCheckboxBgOff.color = 0xAA1A1A1E.toInt()
        pCheckboxBgOff.style = Paint.Style.FILL

        pCheckboxBorderOn.style = Paint.Style.STROKE
        pCheckboxBorderOn.strokeWidth = 2f
        pCheckboxBorderOn.color = 0xFFFFD54F.toInt()

        pCheckboxBorderOff.style = Paint.Style.STROKE
        pCheckboxBorderOff.strokeWidth = 2f
        pCheckboxBorderOff.color = 0xFF444444.toInt()

        ampScaleWidthPx = (32 * density).toInt()
        pAmpScale.color = 0xFF808080.toInt()
        pAmpScale.strokeWidth = 1f
        pAmpText.color = 0xFF808080.toInt()
        pAmpText.textSize = 9f * density
        pHGrid.color = 0x22FFFFFF
        pHGrid.strokeWidth = 1f

        pScaleBg.color = 0xAA0E0E10.toInt()
        pScaleBg.style = Paint.Style.FILL

        pRuler.color = colorRuler
        pRuler.style = Paint.Style.FILL

        pRulerText.color = colorRulerText
        pRulerText.textSize = 10f * density

        pCheck.color = colorCheck
        pCheck.style = Paint.Style.FILL

        pCheckBg.color = 0xAA1A1A1E.toInt()
        pCheckBg.style = Paint.Style.FILL

        pChannelText.color = 0xFF0E0E10.toInt()
        pChannelText.textSize = 16f * density
        pChannelText.typeface = android.graphics.Typeface.DEFAULT_BOLD

        setBackgroundColor(colorBg)

        gestureDetector = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    zoomToFit()
                    return true
                }
                override fun onSingleTapUp(e: MotionEvent): Boolean = false
        })

        scaleDetector = ScaleGestureDetector(c,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    if (totalFrames == 0) return true
                    val oldFpp = viewFramesPerPixel
                    var newFpp = oldFpp / d.scaleFactor
                    val minFpp = 1.0 / 8.0
                    val maxFpp = max(1, totalFrames).toDouble()
                    if (newFpp < minFpp) newFpp = minFpp
                    if (newFpp > maxFpp) newFpp = maxFpp

                    val fx = d.focusX
                    val focusFrame = viewStartFrame + fx * oldFpp
                    viewStartFrame = focusFrame - fx * newFpp
                    viewFramesPerPixel = newFpp
                    clampView()
                    invalidatePeakCache()
                    invalidate()
                    return true
                }
        })
    }
    fun setListener(l: Listener?) {
        listener = l
    }

    fun setStereoView(stereo: Boolean) {
        stereoView = stereo
        invalidatePeakCache()
        invalidate()
    }

    fun setData(pcm: FloatArray?, sampleRate: Int, markers: List<Int>?) {
        this.pcm = pcm
        this.sampleRate = sampleRate
        this.markers = markers
        totalFrames = pcm?.size?.div(2) ?: 0
        viewStartFrame = 0.0
        viewFramesPerPixel = 1.0
        selStart = -1
        selEnd = -1
        playheadFrame = 0
        activePlayFrame = 0
        invalidatePeakCache()
        invalidate()
    }

    fun setZeroCrossingSnap(enabled: Boolean) {
        zeroCrossingSnap = enabled
    }

    fun updatePcm(newPcm: FloatArray?) {
        pcm = newPcm
        totalFrames = newPcm?.size?.div(2) ?: 0
        if (selStart > totalFrames) selStart = -1
        if (selEnd > totalFrames) selEnd = -1
        if (playheadFrame > totalFrames) playheadFrame = totalFrames
        if (activePlayFrame > totalFrames) activePlayFrame = totalFrames
        invalidatePeakCache()
        invalidate()
    }

    fun updateMarkers(newMarkers: List<Int>?) {
        markers = newMarkers
        invalidate()
    }

    fun setPlayheadFrame(f: Int) {
        playheadFrame = max(0, min(totalFrames, f))
        invalidate()
    }

    fun setActivePlayheadFrame(f: Int) {
        activePlayFrame = max(0, min(totalFrames, f))
        invalidate()
    }

    fun snapActivePlayheadFrame(f: Int) {
        activePlayFrame = max(0, min(totalFrames, f))
        invalidate()
    }

    fun getPlayheadFrame(): Int = playheadFrame
    fun getActivePlayFrame(): Int = activePlayFrame

    fun setSelection(start: Int, end: Int) {
        selStart = max(0, min(start, totalFrames))
        selEnd = max(0, min(end, totalFrames))
        if (selStart > selEnd) {
            val t = selStart
            selStart = selEnd
            selEnd = t
        }
        if (selStart == selEnd) {
            selStart = -1
            selEnd = -1
        }
        invalidate()
        listener?.onSelectionChanged(selStart, selEnd)
    }

    fun clearSelection() {
        selStart = -1
        selEnd = -1
        invalidate()
        listener?.onSelectionChanged(-1, -1)
    }

    fun getSelStart(): Int = selStart
    fun getSelEnd(): Int = selEnd
    fun hasSelection(): Boolean = selStart >= 0 && selEnd > selStart

    fun zoomToFit() {
        val w = width - 2 * paddingPx
        if (w <= 0 || totalFrames <= 0) return
        viewStartFrame = 0.0
        viewFramesPerPixel = totalFrames.toDouble() / w
        clampView()
        invalidatePeakCache()
        invalidate()
    }

    fun zoomInAroundCenter(factor: Double) {
        if (totalFrames == 0) return
        val oldFpp = viewFramesPerPixel
        var newFpp = oldFpp / factor
        val minFpp = 1.0 / 8.0
        val maxFpp = max(1, totalFrames).toDouble()
        if (newFpp < minFpp) newFpp = minFpp
        if (newFpp > maxFpp) newFpp = maxFpp
        val cx = width / 2f
        val focusFrame = viewStartFrame + cx * oldFpp
        viewStartFrame = focusFrame - cx * newFpp
        viewFramesPerPixel = newFpp
        clampView()
        invalidatePeakCache()
        invalidate()
    }

    fun centerOnFrame(frame: Int) {
        val w = width
        if (w <= 0) return
        viewStartFrame = frame - (w / 2.0) * viewFramesPerPixel
        clampView()
        invalidatePeakCache()
        invalidate()
    }

    /** F11: включить режим перетаскивания маркера. */
    fun startMarkerDrag(frame: Int) {
        val m = markers ?: return
        if (!m.contains(frame)) return
        dragMode = DRAG_MARKER
        dragMarkerOldFrame = frame
        dragMarkerFrame = frame
        invalidate()
    }

    /** F11: отменить режим перетаскивания. */
    fun cancelMarkerDrag() {
        if (dragMode == DRAG_MARKER) {
            dragMode = DRAG_NONE
            dragMarkerOldFrame = -1
            dragMarkerFrame = -1
            invalidate()
        }
    }

    fun updateExportIntervals(intervals: List<IntArray>?) {
        exportIntervals = intervals
        invalidate()
    }

    fun setIntervalsEditMode(enabled: Boolean) {
        intervalsEditMode = enabled
        intervalPage = 0
        selectedIntervalIndex = -1
        justEnteredIntervalsMode = false // Сброс флага при входе/выходе
        invalidate()
    }

    fun isIntervalsEditMode(): Boolean = intervalsEditMode

    /** Возвращает количество интервалов. */
    fun getIntervalCount(): Int = exportIntervals?.size ?: 0

    /** Возвращает размер страницы для перелистывания. */
    fun getIntervalPageSize(): Int = intervalPageSize

    fun nextIntervalPage() {
        val ei = exportIntervals ?: return
        val maxPage = (ei.size - 1) / intervalPageSize
        if (intervalPage < maxPage) {
            intervalPage++
            selectedIntervalIndex = -1
            invalidate()
        }
    }

    fun prevIntervalPage() {
        if (intervalPage > 0) {
            intervalPage--
            selectedIntervalIndex = -1
            invalidate()
        }
    }

    fun isZeroCrossingSnap(): Boolean = zeroCrossingSnap
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector?.onTouchEvent(e)
        gestureDetector?.onTouchEvent(e)

        if (e.actionMasked == MotionEvent.ACTION_DOWN && e.y < rulerHeightPx) {
            playheadFrame = xToFrame(e.x)
            invalidate()
            listener?.onPlayheadDragged(playheadFrame)
            dragMode = DRAG_NONE
            return true
        }

        return when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleActionDown(e)
            MotionEvent.ACTION_MOVE -> handleActionMove(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handleActionUp(e)
            else -> super.onTouchEvent(e)
        }
    }

    private fun handleActionDown(e: MotionEvent): Boolean {
        // F11: drag маркера — маркер прыгает в точку касания
        if (dragMode == DRAG_MARKER) {
            var f = xToFrame(e.x)
            if (zeroCrossingSnap && pcm != null) f = nearestZeroCross(f)
            dragMarkerFrame = f
            invalidate()
            lastTouchX = e.x
            downX = e.x
            downY = e.y
            return true
        }

        lastTouchX = e.x
        downX = e.x
        downY = e.y
        downSelStart = selStart
        downSelEnd = selEnd

        // 0. Интервалы
        val ei = exportIntervals
        if (ei != null && ei.isNotEmpty()) {
            val layout = calcStripLayout()
            val startY = layout[0]
            val stripH = layout[1]
            val count = layout[2].toInt()
            val startIdx = layout[3].toInt()
            val endIdx = layout[4].toInt()

            if (count > 0) {
                val stepY = stripH + 8f
                val tapAbove = stepY / 2f
                val tapBelow = stepY / 2f
                val waveW = width - 2 * paddingPx
                var offsetY = startY
                for (i in startIdx until endIdx) {
                    val interval = ei[i]
                    val x1 = frameToX(interval[0])
                    val x2 = frameToX(interval[1])
                    val drawX1 = max(0f, x1) + paddingPx
                    val drawX2 = min(waveW.toFloat(), x2) + paddingPx

                    val tapTop = offsetY - tapAbove
                    val tapBottom = offsetY + stripH + tapBelow

                    if (e.x >= drawX1 && e.x <= drawX2 && e.y >= tapTop && e.y <= tapBottom) {
                        if (!intervalsEditMode) {
                            setIntervalsEditMode(true)
                            justEnteredIntervalsMode = true // <-- Флаг, что только что вошли
                            listener?.onIntervalsModeChanged(true)
                        } else {
                            selectedIntervalIndex = i
                            invalidate()
                        }
                        downIntervalStart = interval[0]
                        downIntervalEnd = interval[1]
                        longPressActivated = false
                        postDelayed(longPressRunnable, 350)
                        return true
                    }
                    offsetY += stepY
                }
            }
        }

        // 1. Чекбоксы L/R
        if (stereoView) {
            if (e.x >= checkLx && e.x <= checkLx + checkSize && e.y >= checkLy
                && e.y <= checkLy + checkSize) {
                if (!channelR) return true
                channelL = !channelL
                invalidate()
                listener?.onChannelToggled(channelL, channelR)
                return true
            }
            if (e.x >= checkRx && e.x <= checkRx + checkSize && e.y >= checkRy
                && e.y <= checkRy + checkSize) {
                if (!channelL) return true
                channelR = !channelR
                invalidate()
                listener?.onChannelToggled(channelL, channelR)
                return true
            }
        }

        // 2. Ручки выделения
        if (hasSelection() && e.y >= rulerHeightPx) {
            val sxStart = frameToX(selStart) + paddingPx
            val sxEnd = frameToX(selEnd) + paddingPx
            if (abs(e.x - sxStart) <= handleHalfPx * 2) {
                dragMode = DRAG_SEL_START
                return true
            }
            if (abs(e.x - sxEnd) <= handleHalfPx * 2) {
                dragMode = DRAG_SEL_END
                return true
            }
        }

        // 3. Маркеры
        downMarkerFrame = -1
        val m = markers
        if (m != null) {
            for (mk in m) {
                val mx = frameToX(mk) + paddingPx
                if (e.x >= mx - 20 && e.x <= mx + markerFlagW + 20) {
                    downMarkerFrame = mk
                    break
                }
            }
        }

        // 4. Playhead
        val playX = frameToX(playheadFrame) + paddingPx
        val onPlayhead = abs(e.x - playX) <= 20

        if (downMarkerFrame != -1) {
            dragMode = if (onPlayhead) DRAG_PLAYHEAD else DRAG_SCROLL
            longPressActivated = false
            postDelayed(longPressRunnable, 350)
            return true
        }

        if (onPlayhead) {
            dragMode = DRAG_PLAYHEAD
            return true
        }

        // 5. Скролл
        dragMode = DRAG_SCROLL
        longPressActivated = false
        postDelayed(longPressRunnable, 350)
        return true
    }

    private fun handleActionMove(e: MotionEvent): Boolean {
        if (scaleDetector?.isInProgress == true) return true

        if (dragMode == DRAG_MARKER) {
            var f = xToFrame(e.x)
            if (zeroCrossingSnap && pcm != null) f = nearestZeroCross(f)
            dragMarkerFrame = f
            invalidate()
            return true
        }

        if (!longPressActivated && abs(e.x - downX) + abs(e.y - downY) > 12) {
            removeCallbacks(longPressRunnable)
            downIntervalStart = -1
            downIntervalEnd = -1
        }

        if (dragMode == DRAG_PLAYHEAD) {
            var f = xToFrame(e.x)
            val marker = nearestMarker(f, 15)
            if (marker != -1) f = marker
            playheadFrame = f
            invalidate()
            listener?.onPlayheadDragged(playheadFrame)
            return true
        }

        if (dragMode == DRAG_SCROLL) {
            val dx = lastTouchX - e.x
            viewStartFrame += dx * viewFramesPerPixel
            clampView()
            lastTouchX = e.x
            invalidatePeakCache()
            invalidate()
            return true
        }

        if (dragMode == DRAG_SEL_START) {
            var f = xToFrame(e.x)
            val marker = nearestMarker(f, 15)
            if (marker != -1) f = marker
            val newStart = min(f, selEnd)
            val newEnd = max(f, selEnd)
            if (newStart != selStart) {
                selStart = max(0, newStart)
                listener?.onSelectionChanged(selStart, selEnd)
                invalidate()
            }
            return true
        }

        if (dragMode == DRAG_SEL_END) {
            var f = xToFrame(e.x)
            val marker = nearestMarker(f, 15)
            if (marker != -1) f = marker
            val newStart = min(selStart, f)
            val newEnd = max(selStart, f)
            if (newEnd != selEnd) {
                selEnd = min(totalFrames, newEnd)
                listener?.onSelectionChanged(selStart, selEnd)
                invalidate()
            }
            return true
        }

        if (dragMode == DRAG_NEW_SELECTION) {
            val f = xToFrame(e.x)
            val a = min(downSelStart, f)
            val b = max(downSelStart, f)
            selStart = max(0, a)
            selEnd = min(totalFrames, b)
            listener?.onSelectionChanged(selStart, selEnd)
            invalidate()
            return true
        }

        return true
    }

    private fun handleActionUp(e: MotionEvent): Boolean {
        // F11: финализация drag
        if (dragMode == DRAG_MARKER) {
            val old = dragMarkerOldFrame
            val nw = dragMarkerFrame
            dragMode = DRAG_NONE
            dragMarkerOldFrame = -1
            dragMarkerFrame = -1
            downMarkerFrame = -1
            if (old != -1 && nw != -1 && old != nw) {
                listener?.onMarkerMoved(old, nw)
            } else {
                invalidate()
            }
            return true
        }

        removeCallbacks(longPressRunnable)

        // Снап выделения
        if (hasSelection()) {
            val mStart = nearestMarker(selStart, 15)
            val mEnd = nearestMarker(selEnd, 15)
            if (mStart != -1) selStart = mStart
            if (mEnd != -1) selEnd = mEnd

            if (zeroCrossingSnap && pcm != null) {
                if (mStart == -1 && selStart >= 0 && selStart < totalFrames)
                selStart = nearestZeroCross(selStart)
                if (mEnd == -1 && selEnd >= 0 && selEnd < totalFrames)
                selEnd = nearestZeroCross(selEnd)
            }
            if (selStart > selEnd) {
                val t = selStart
                selStart = selEnd
                selEnd = t
            }
            invalidate()
            listener?.onSelectionChanged(selStart, selEnd)
        }

        // Выход из режима интервалов
        if (intervalsEditMode && selectedIntervalIndex != -1 && !longPressActivated) {
            if (justEnteredIntervalsMode) {
                justEnteredIntervalsMode = false // Пропускаем первый тап
            } else {
                if (abs(e.x - downX) < 8 && abs(e.y - downY) < 8) {
                    val layout = calcStripLayout()
                    val startY = layout[0]
                    val stripH = layout[1]
                    val count = layout[2].toInt()
                    val startIdx = layout[3].toInt()
                    val endIdx = layout[4].toInt()
                    val stepY = stripH + 8f

                    if (count > 0 && selectedIntervalIndex >= startIdx && selectedIntervalIndex < endIdx) {
                        val offsetY = startY + (selectedIntervalIndex - startIdx) * stepY
                        val ei = exportIntervals
                        if (ei != null) {
                            val interval = ei[selectedIntervalIndex]
                            val waveW = width - 2 * paddingPx
                            val drawX1 = max(0f, frameToX(interval[0])) + paddingPx
                            val drawX2 = min(waveW.toFloat(), frameToX(interval[1])) + paddingPx
                            val tapTop = offsetY - stepY / 2f
                            val tapBottom = offsetY + stripH + stepY / 2f
                            if (e.x >= drawX1 && e.x <= drawX2 && e.y >= tapTop && e.y <= tapBottom) {
                                setIntervalsEditMode(false)
                                listener?.onIntervalsModeChanged(false)
                                return true
                            }
                        }
                    }
                }
            }
        }

        // Короткий тап
        if (e.actionMasked == MotionEvent.ACTION_UP && abs(e.x - downX) < 8
            && abs(e.y - downY) < 8 && e.y >= rulerHeightPx && !longPressActivated) {

            if (downMarkerFrame != -1) {
                listener?.onMarkerTapped(downMarkerFrame)
            } else {
                var frame = xToFrame(e.x)
                val marker = nearestMarker(frame, 15)
                if (marker != -1) frame = marker
                listener?.onTap(frame)
            }
        }

        dragMode = DRAG_NONE
        downMarkerFrame = -1
        longPressActivated = false
        downIntervalStart = -1
        downIntervalEnd = -1
        return true
    }

    private fun xToFrame(x: Float): Int {
        val f = viewStartFrame + (x - paddingPx) * viewFramesPerPixel
        return max(0, min(totalFrames, f.roundToInt()))
    }

    private fun frameToX(frame: Int): Float {
        return ((frame - viewStartFrame) / viewFramesPerPixel).toFloat()
    }

    private fun clampView() {
        if (totalFrames <= 0) {
            viewStartFrame = 0.0
            return
        }
        val visibleWidth = width - 2 * paddingPx
        if (visibleWidth <= 0) return
        val visibleFrames = visibleWidth * viewFramesPerPixel
        val minStart = -visibleFrames / 2.0
        val baseMaxStart = max(0.0, totalFrames - visibleFrames)
        val maxStart = baseMaxStart + visibleFrames / 2.0
        if (viewStartFrame < minStart) viewStartFrame = minStart
        if (viewStartFrame > maxStart) viewStartFrame = maxStart
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && totalFrames > 0) {
            if (viewFramesPerPixel <= 1) zoomToFit()
        }
        updateCheckboxPositions(w, h)
        invalidatePeakCache()
    }

    private fun updateCheckboxPositions(w: Int, h: Int) {
        val waveTop = rulerHeightPx
        val waveH = h - waveTop
        if (waveH <= 0 || w <= 0) {
            checkLx = 0f; checkLy = 0f; checkRx = 0f; checkRy = 0f
            return
        }
        checkLx = (w - checkSize - 25).toFloat()
        checkLy = (waveTop + waveH / 2 - checkSize - 25).toFloat()
        checkRx = (w - checkSize - 25).toFloat()
        checkRy = (waveTop + waveH / 2 + 25).toFloat()
    }

    private fun invalidatePeakCache() {
        peakCacheWidth = 0
    }

    private fun buildPeakCache(width: Int) {
        val p = pcm
        if (p == null || totalFrames == 0 || width <= 0) {
            peakCacheWidth = width
            return
        }
        peakMin = FloatArray(width)
        peakMax = FloatArray(width)
        peakMinR = FloatArray(width)
        peakMaxR = FloatArray(width)
        peakMinMono = FloatArray(width)
        peakMaxMono = FloatArray(width)

        val start = viewStartFrame
        val fpp = viewFramesPerPixel

        for (x in 0 until width) {
            var f0 = floor(start + x * fpp).toInt()
            var f1 = floor(start + (x + 1) * fpp).toInt()
            if (f1 <= f0) f1 = f0 + 1
            if (f0 < 0) f0 = 0
            if (f1 > totalFrames) f1 = totalFrames

            var minL = 0f; var maxL = 0f
            var minR = 0f; var maxR = 0f

            val span = f1 - f0
            val step = max(1, span / 64)

            var f = f0
            while (f < f1) {
                val l = p[f * 2]
                val r = p[f * 2 + 1]
                if (l < minL) minL = l
                if (l > maxL) maxL = l
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                f += step
            }

            if (f1 > f0) {
                val last = f1 - 1
                val l = p[last * 2]
                val r = p[last * 2 + 1]
                if (l < minL) minL = l
                if (l > maxL) maxL = l
                if (r < minR) minR = r
                if (r > maxR) maxR = r
            }

            peakMin!![x] = minL
            peakMax!![x] = maxL
            peakMinR!![x] = minR
            peakMaxR!![x] = maxR
            peakMinMono!![x] = min(minL, minR)
            peakMaxMono!![x] = max(maxL, maxR)
        }
        peakCacheWidth = width
        peakCacheStart = start
        peakCacheFpp = fpp
    }

    private fun nearestZeroCross(frame: Int): Int {
        val p = pcm
        if (p == null || totalFrames == 0) return frame
        val f = max(0, min(totalFrames - 1, frame))
        val search = min(2048, totalFrames / 2)
        var best = f
        var bestDist = Int.MAX_VALUE
        for (d in 0..search) {
            val a = f - d
            val b = f + d
            if (a >= 0 && isZeroCrossAt(a)) {
                if (d < bestDist) { bestDist = d; best = a }
            }
            if (b < totalFrames && isZeroCrossAt(b)) {
                if (d < bestDist) { bestDist = d; best = b }
            }
            if (bestDist <= 1) break
        }
        return best
    }

    private fun nearestMarker(frame: Int, tolerancePx: Int): Int {
        val m = markers
        if (m == null || m.isEmpty()) return -1
        val targetX = frameToX(frame)
        var bestMarker = -1
        var bestDist = tolerancePx.toFloat()
        for (mk in m) {
            val mx = frameToX(mk)
            val dist = abs(targetX - mx)
            if (dist < bestDist) {
                bestDist = dist
                bestMarker = mk
            }
        }
        return bestMarker
    }

    private fun isZeroCrossAt(frame: Int): Boolean {
        val p = pcm ?: return true
        if (frame <= 0 || frame >= totalFrames) return true
        val prevL = p[(frame - 1) * 2]
        val prevR = p[(frame - 1) * 2 + 1]
        val curL = p[frame * 2]
        val curR = p[frame * 2 + 1]
        val prevSum = prevL + prevR
        val curSum = curL + curR
        return (prevSum <= 0f && curSum >= 0f) || (prevSum >= 0f && curSum <= 0f)
    }
    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val rulerH = rulerHeightPx
        val waveTop = rulerH
        val waveBottom = h
        val waveH = waveBottom - waveTop
        val centerY = waveTop + waveH / 2

        canvas.drawRect(0f, 0f, w.toFloat(), rulerH.toFloat(), pRuler)

        canvas.save()
        canvas.translate(paddingPx.toFloat(), 0f)
        val waveWidth = w - 2 * paddingPx
        lastWaveTop = waveTop
        lastWaveBottom = waveBottom
        lastWaveWidth = waveWidth

        drawRuler(canvas, waveWidth, rulerH)

        if (pcm == null || totalFrames == 0) {
            drawCenterLine(canvas, centerY, waveWidth)
            canvas.restore()
            return
        }

        drawGrid(canvas, waveWidth, waveTop, waveBottom)

        val zeroX = frameToX(0)
        val endX = frameToX(totalFrames)
        val startX = max(0, zeroX.toInt())
        val endXInt = min(waveWidth, endX.toInt())

        drawEmptySpace(canvas, zeroX, endX, waveWidth, waveTop, waveBottom)

        if (peakCacheWidth != waveWidth || peakCacheStart != viewStartFrame
            || peakCacheFpp != viewFramesPerPixel) {
            buildPeakCache(waveWidth)
        }

        drawLevels(canvas, waveTop, waveBottom, waveH, centerY, startX, endXInt)
        drawSelectionBackground(canvas, waveTop, waveBottom)
        drawWaveform(canvas, waveWidth, waveTop, waveBottom, waveH, centerY, startX, endXInt)
        drawSelectionBorder(canvas, waveTop, waveBottom)
        drawMarkers(canvas, waveWidth, waveTop, waveBottom)
        drawExportIntervals(canvas, waveWidth, waveTop)

        if (intervalsEditMode && exportIntervals != null) {
            drawIntervalsEditMode(canvas, waveWidth, waveTop, waveBottom)
        }

        drawPlayheads(canvas, waveWidth, waveTop, waveBottom)

        canvas.restore()

        drawAmpScale(canvas, w - ampScaleWidthPx, w, waveTop, waveBottom, waveH)
        drawChannelCheckboxes(canvas, w, waveTop, waveH)
    }

    private fun drawEmptySpace(canvas: Canvas, zeroX: Float, endX: Float, waveWidth: Int,
        waveTop: Int, waveBottom: Int) {
        if (zeroX > 0) canvas.drawRect(0f, waveTop.toFloat(), zeroX, waveBottom.toFloat(), pEmpty)
        if (endX < waveWidth) canvas.drawRect(endX, waveTop.toFloat(), waveWidth.toFloat(),
            waveBottom.toFloat(), pEmpty)
    }

    private fun drawLevels(canvas: Canvas, waveTop: Int, waveBottom: Int, waveH: Int,
        centerY: Int, startX: Int, endXInt: Int) {
        if (startX >= endXInt) return
        if (stereoView) {
            drawHorizontalLevels(canvas, waveTop + waveH / 4, waveH * 0.25f - 2, waveTop,
                waveTop + waveH / 2, startX, endXInt)
            drawHorizontalLevels(canvas, waveTop + 3 * waveH / 4, waveH * 0.25f - 2,
                waveTop + waveH / 2, waveBottom, startX, endXInt)
        } else {
            drawHorizontalLevels(canvas, centerY, waveH * 0.5f - 2, waveTop, waveBottom, startX,
                endXInt)
        }
    }

    private fun drawSelectionBackground(canvas: Canvas, waveTop: Int, waveBottom: Int) {
        if (!hasSelection()) return
        val sx0 = frameToX(selStart)
        val sx1 = frameToX(selEnd)
        canvas.drawRect(sx0, waveTop.toFloat(), sx1, waveBottom.toFloat(), pSel)
    }

    private fun drawWaveform(canvas: Canvas, waveWidth: Int, waveTop: Int, waveBottom: Int,
        waveH: Int, centerY: Int, startX: Int, endXInt: Int) {
        val pMin = peakMin
        val pMax = peakMax
        if (pMin == null || pMax == null || startX >= endXInt) return

        if (stereoView) {
            val halfH = waveH * 0.25f - 2
            val topCenter = waveTop + waveH / 4
            val botCenter = waveTop + 3 * waveH / 4
            drawWaveChannel(canvas, pMax, pMin, waveWidth, startX, endXInt, topCenter, halfH)
            drawWaveChannel(canvas, peakMaxR!!, peakMinR!!, waveWidth, startX, endXInt, botCenter,
                halfH)
            drawCenterLine(canvas, centerY, waveWidth)
        } else {
            val halfH = waveH * 0.5f - 2
            drawWaveChannel(canvas, peakMaxMono!!, peakMinMono!!, waveWidth, startX, endXInt,
                centerY, halfH)
            drawCenterLine(canvas, centerY, waveWidth)
        }
    }

    private fun drawWaveChannel(canvas: Canvas, max: FloatArray, min: FloatArray, waveWidth: Int,
        startX: Int, endXInt: Int, centerY: Int, halfH: Float) {
        wavePath.reset()
        for (x in startX until endXInt) {
            val y = centerY - max[x] * halfH
            if (x == startX) wavePath.moveTo(x.toFloat(), y)
            else wavePath.lineTo(x.toFloat(), y)
        }
        for (x in endXInt - 1 downTo startX) {
            val y = centerY - min[x] * halfH
            wavePath.lineTo(x.toFloat(), y)
        }
        wavePath.close()
        canvas.drawPath(wavePath, pWaveFill)
        canvas.drawPath(wavePath, pWave)
    }

    private fun drawSelectionBorder(canvas: Canvas, waveTop: Int, waveBottom: Int) {
        if (!hasSelection()) return
        val sx0 = frameToX(selStart)
        val sx1 = frameToX(selEnd)
        canvas.drawLine(sx0, waveTop.toFloat(), sx0, waveBottom.toFloat(), pSelBorder)
        canvas.drawLine(sx1, waveTop.toFloat(), sx1, waveBottom.toFloat(), pSelBorder)
        drawHandle(canvas, sx0, waveTop, waveBottom)
        drawHandle(canvas, sx1, waveTop, waveBottom)
    }

    /** B11: номера на верхнем и нижнем флажках. F11: «призрак» при drag. */
    private fun drawMarkers(canvas: Canvas, waveWidth: Int, waveTop: Int, waveBottom: Int) {
        val m = markers ?: return
        var num = 1
        for (mk in m) {
            val drawFrame = if (dragMode == DRAG_MARKER && mk == dragMarkerOldFrame)
            dragMarkerFrame else mk
            val x = frameToX(drawFrame)
            if (x < -markerFlagW || x > waveWidth + markerFlagW) {
                num++
                continue
            }
            canvas.drawLine(x, waveTop.toFloat(), x, waveBottom.toFloat(), pMarker)

            val flag = Path()
            flag.moveTo(x, (waveTop + 2).toFloat())
            flag.lineTo((x + markerFlagW), (waveTop + 2).toFloat())
            flag.lineTo((x + markerFlagW), (waveTop + 2 + markerFlagH / 2).toFloat())
            flag.lineTo(x, (waveTop + 2 + markerFlagH).toFloat())
            flag.close()
            canvas.drawPath(flag, pMarkerFill)

            val botY = (waveBottom - markerFlagH - 2).toFloat()
            val flag2 = Path()
            flag2.moveTo(x, botY)
            flag2.lineTo(x + markerFlagW, botY)
            flag2.lineTo(x + markerFlagW, botY + markerFlagH / 2f)
            flag2.lineTo(x, botY + markerFlagH)
            flag2.close()
            canvas.drawPath(flag2, pMarkerFill)

            val label = num.toString()
            val textW = pMarkerText.measureText(label)
            val textX = x + markerFlagW / 2f - textW / 2f
            val textSize = pMarkerText.textSize

            val topTextY = waveTop + 2 + markerFlagH / 2f + textSize / 3f
            canvas.drawText(label, textX, topTextY, pMarkerText)

            val botTextY = botY + markerFlagH / 2f + textSize / 3f
            canvas.drawText(label, textX, botTextY, pMarkerText)

            num++
        }
    }

    private fun drawPlayheads(canvas: Canvas, waveWidth: Int, waveTop: Int, waveBottom: Int) {
        val px = frameToX(playheadFrame)
        if (px >= 0 && px <= waveWidth) {
            canvas.drawLine(px, waveTop.toFloat(), px, waveBottom.toFloat(), pPlayhead)
            canvas.drawRect(px - 5, waveTop.toFloat(), px + 5, (waveTop + 8).toFloat(), pPlayhead)
        }
        val activePx = frameToX(activePlayFrame)
        if (activePx >= 0 && activePx <= waveWidth && activePlayFrame != playheadFrame) {
            canvas.drawLine(activePx, waveTop.toFloat(), activePx, waveBottom.toFloat(),
                pActivePlayhead)
        }
    }

    private fun drawChannelCheckboxes(canvas: Canvas, w: Int, waveTop: Int, waveH: Int) {
        if (!stereoView) return
        if (checkLx == 0f && checkLy == 0f) updateCheckboxPositions(w, height)

        drawCheckbox(canvas, checkLx, checkLy, channelL)
        pChannelText.color = if (channelL) 0xFF0E0E10.toInt() else 0xFFE8E8EA.toInt()
        canvas.drawText("L", checkLx + checkSize / 2f - 10, checkLy + checkSize / 2f + 10,
            pChannelText)

        drawCheckbox(canvas, checkRx, checkRy, channelR)
        pChannelText.color = if (channelR) 0xFF0E0E10.toInt() else 0xFFE8E8EA.toInt()
        canvas.drawText("R", checkRx + checkSize / 2f - 10, checkRy + checkSize / 2f + 10,
            pChannelText)
    }

    private fun drawExportIntervals(canvas: Canvas, waveWidth: Int, waveTop: Int) {
        val ei = exportIntervals
        if (ei == null || ei.isEmpty()) return
        if (intervalsEditMode) return

        val layout = calcStripLayout()
        val startY = layout[0]
        val stripH = 8f
        val count = layout[2].toInt()
        val startIdx = layout[3].toInt()
        val endIdx = layout[4].toInt()

        if (count <= 0) return

        var offsetY = startY
        for (i in startIdx until endIdx) {
            val interval = ei[i]
            val x1 = frameToX(interval[0])
            val x2 = frameToX(interval[1])

            if (x2 < 0 || x1 > waveWidth) {
                offsetY += stripH + 8f
                continue
            }

            val drawX1 = max(0f, x1)
            val drawX2 = min(waveWidth.toFloat(), x2)

            tmpRect.set(drawX1, offsetY, drawX2, offsetY + stripH)
            canvas.drawRoundRect(tmpRect, 4f, 4f, pIntervalFillNormal)

            offsetY += stripH + 8f
        }
    }

    private fun drawCheckbox(c: Canvas, x: Float, y: Float, checked: Boolean) {
        val cornerRadius = 8f * resources.displayMetrics.density
        tmpRect.set(x, y, x + checkSize, y + checkSize)
        c.drawRoundRect(tmpRect, cornerRadius, cornerRadius,
            if (checked) pCheckboxBgOn else pCheckboxBgOff)
        c.drawRoundRect(tmpRect, cornerRadius, cornerRadius,
            if (checked) pCheckboxBorderOn else pCheckboxBorderOff)
    }

    private fun chooseStep(visibleSec: Double): Double {
        val steps = doubleArrayOf(0.01, 0.05, 0.1, 0.5, 1.0, 5.0, 10.0, 30.0, 60.0)
        var step = steps[steps.size - 1]
        for (s in steps) {
            if (visibleSec / s < 12) { step = s; break }
        }
        return step
    }

    private fun drawRuler(canvas: Canvas, w: Int, rulerH: Int) {
        if (sampleRate <= 0) return
        val visibleSec = w * viewFramesPerPixel / sampleRate
        val step = chooseStep(visibleSec)
        val startSec = viewStartFrame / sampleRate
        val firstMark = ceil(startSec / step) * step
        val endSec = (viewStartFrame + w * viewFramesPerPixel) / sampleRate

        var t = firstMark
        while (t <= endSec) {
            if (t >= 0 && t * sampleRate <= totalFrames) {
                val x = ((t * sampleRate - viewStartFrame) / viewFramesPerPixel).toFloat()
                if (x in 0f..w.toFloat()) {
                    canvas.drawLine(x, (rulerH - 6).toFloat(), x, rulerH.toFloat(), pGrid)
                    val label = if (step < 1.0) {
                        String.format(Locale.US, "%.0fms", t * 1000)
                    } else {
                        val sec = t.toInt()
                        "${sec / 60}:" + String.format(Locale.US, "%02d", sec % 60)
                    }
                    canvas.drawText(label, x + 3, (rulerH - 9).toFloat(), pRulerText)
                }
            }
            t += step
        }
    }

    private fun drawGrid(canvas: Canvas, w: Int, top: Int, bottom: Int) {
        if (sampleRate <= 0) return
        val visibleSec = w * viewFramesPerPixel / sampleRate
        val step = chooseStep(visibleSec)
        val startSec = viewStartFrame / sampleRate
        val firstMark = ceil(startSec / step) * step
        val endSec = (viewStartFrame + w * viewFramesPerPixel) / sampleRate

        var t = firstMark
        while (t <= endSec) {
            if (t >= 0 && t * sampleRate <= totalFrames) {
                val x = ((t * sampleRate - viewStartFrame) / viewFramesPerPixel).toFloat()
                if (x in 0f..w.toFloat()) {
                    canvas.drawLine(x, top.toFloat(), x, bottom.toFloat(), pGrid)
                }
            }
            t += step
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, top: Int, bottom: Int) {
        val cyTop = (top + handleHalfPx).toFloat()
        val cyBot = (bottom - handleHalfPx).toFloat()
        canvas.drawCircle(x, cyTop, handleHalfPx.toFloat(), pSelBorder)
        canvas.drawCircle(x, cyBot, handleHalfPx.toFloat(), pSelBorder)
        canvas.drawLine(x, cyTop, x, cyBot, pSelBorder)
    }

    private fun drawHorizontalLevels(canvas: Canvas, centerY: Int, halfH: Float, top: Int,
        bottom: Int, x0: Int, x1: Int) {
        val levels = floatArrayOf(0f, 0.5f, 0.7f, 0.9f, 0.999f)
        for (amp in levels) {
            val dy = amp * halfH
            val yTop = centerY - dy
            if (yTop >= top && yTop <= bottom) {
                canvas.drawLine(x0.toFloat(), yTop, x1.toFloat(), yTop, pHGrid)
            }
            val yBot = centerY + dy
            if (yBot >= top && yBot <= bottom) {
                canvas.drawLine(x0.toFloat(), yBot, x1.toFloat(), yBot, pHGrid)
            }
        }
    }

    private fun drawCenterLine(canvas: Canvas, centerY: Int, waveWidth: Int) {
        val zeroX = frameToX(0)
        val endX = frameToX(totalFrames)
        val startDraw = max(0f, zeroX)
        val endDraw = min(waveWidth.toFloat(), endX)
        if (startDraw < endDraw) {
            canvas.drawLine(startDraw, centerY.toFloat(), endDraw, centerY.toFloat(), pCenter)
        }
    }

    private fun drawAmpScale(canvas: Canvas, scaleX: Int, w: Int, top: Int, bottom: Int,
        waveH: Int) {
        canvas.drawRect(scaleX.toFloat(), top.toFloat(), w.toFloat(), bottom.toFloat(), pScaleBg)
        val halfH = waveH * 0.5f - 2
        if (stereoView) {
            drawScaleHalf(canvas, scaleX, w, top, top + waveH / 2, waveH * 0.25f - 2)
            drawScaleHalf(canvas, scaleX, w, top + waveH / 2, bottom, waveH * 0.25f - 2)
        } else {
            drawScaleHalf(canvas, scaleX, w, top, bottom, halfH)
        }
    }

    private fun drawScaleHalf(canvas: Canvas, scaleX: Int, w: Int, top: Int, bottom: Int,
        halfH: Float) {
        val centerY = (top + bottom) / 2
        val levels = floatArrayOf(0f, 0.5f, 0.7f, 0.9f, 0.999f)
        val labels = arrayOf("-inf", "-6", "-3", "-1", "0")
        for (i in levels.indices) {
            val amp = levels[i]
            val dy = amp * halfH
            val yTop = centerY - dy
            val yBot = centerY + dy
            if (yTop >= top && yTop <= bottom) {
                canvas.drawLine(scaleX.toFloat(), yTop, (scaleX + 6).toFloat(), yTop, pAmpScale)
                canvas.drawText(labels[i], (scaleX + 8).toFloat(), yTop + 3, pAmpText)
            }
            if (i > 0 && yBot >= top && yBot <= bottom) {
                canvas.drawLine(scaleX.toFloat(), yBot, (scaleX + 6).toFloat(), yBot, pAmpScale)
            }
        }
    }
    private fun drawIntervalsEditMode(canvas: Canvas, waveWidth: Int, waveTop: Int,
                                      waveBottom: Int) {
        val ei = exportIntervals
        if (ei == null || ei.isEmpty()) return

        val layout = calcStripLayout()
        val startY = layout[0]
        val stripH = layout[1]
        val count = layout[2].toInt()
        val startIdx = layout[3].toInt()
        val endIdx = layout[4].toInt()

        if (count <= 0) return

        var offsetY = startY
        for (i in startIdx until endIdx) {
            val interval = ei[i]
            val x1 = frameToX(interval[0])
            val x2 = frameToX(interval[1])

            if (x2 < 0 || x1 > waveWidth) {
                offsetY += stripH + 8f
                continue
            }

            val drawX1 = max(0f, x1)
            val drawX2 = min(waveWidth.toFloat(), x2)

            tmpRect.set(drawX1, offsetY, drawX2, offsetY + stripH)

            if (i == selectedIntervalIndex) {
                canvas.drawRoundRect(tmpRect, 6f, 6f, pIntervalFill)
                canvas.drawRoundRect(tmpRect, 6f, 6f, pIntervalStroke)
            } else {
                canvas.drawRoundRect(tmpRect, 6f, 6f, pIntervalFill)
            }

            val label = "${i + 1}: ${TimeFormat.formatMsShort(interval[0] * 1000 / sampleRate)} – " +
                    TimeFormat.formatMsShort(interval[1] * 1000 / sampleRate)
            canvas.drawText(label, drawX1 + 8, offsetY + stripH / 2 + 4, pIntervalText)

            offsetY += stripH + 8f
        }
    }

    /** B5: раскладка полос интервалов без зависимости от onDraw. */
    private fun calcStripLayout(): FloatArray {
        val ei = exportIntervals ?: return floatArrayOf(0f, 8f, 0f, 0f, 0f)
        val startIdx = intervalPage * intervalPageSize
        val endIdx = min(startIdx + intervalPageSize, ei.size)
        val count = endIdx - startIdx

        val waveTop = rulerHeightPx
        val waveH = height - waveTop
        if (waveH <= 0) {
            return floatArrayOf(0f, 8f, 0f, startIdx.toFloat(), endIdx.toFloat())
        }

        val stripH: Float
        if (intervalsEditMode) {
            stripH = min(40f, waveH.toFloat() / max(1, count + 1))
                .coerceAtLeast(24f)
        } else {
            stripH = 8f
        }

        val totalH = count * stripH + max(0, count - 1) * 8f
        val startY = waveTop + (waveH - totalH) / 2f

        return floatArrayOf(startY, stripH, count.toFloat(), startIdx.toFloat(),
            endIdx.toFloat())
    }
}
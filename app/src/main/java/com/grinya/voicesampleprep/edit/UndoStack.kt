package com.grinya.voicesampleprep.edit

import java.util.ArrayDeque

/**
 * История состояний PCM. Хранит снапшоты float[].
 * Ограничение по суммарной памяти (по умолчанию 64 МБ — с запасом для 1-мин стерео).
 */
class UndoStack @JvmOverloads constructor(
    private val maxBytes: Long = 64L * 1024 * 1024, // 64 МБ
) {

    class Snapshot(
        val pcm: FloatArray,
        markers: List<Int>,
        exportIntervals: List<IntArray>?,
    ) {
        val markers: MutableList<Int> = ArrayList(markers)
        val exportIntervals: MutableList<IntArray> = ArrayList()

        init {
            if (exportIntervals != null) {
                for (i in exportIntervals) {
                    this.exportIntervals.add(intArrayOf(i[0], i[1]))
                }
            }
        }
    }

    private val undo = ArrayDeque<Snapshot>()
    private val redo = ArrayDeque<Snapshot>()
    private var currentBytes: Long = 0

    /** Сохранить состояние ПЕРЕД изменением. */
    fun push(pcm: FloatArray, markers: List<Int>, exportIntervals: List<IntArray>?) {
        val s = Snapshot(pcm, markers, exportIntervals)
        undo.push(s)
        currentBytes += pcm.size.toLong() * 4
        redo.clear()
        // выбрасываем старейшие, если превысили лимит (оставляем хотя бы 1)
        while (currentBytes > maxBytes && undo.size > 1) {
            val old = undo.removeLast()
            currentBytes -= old.pcm.size.toLong() * 4
        }
    }

    fun canUndo(): Boolean = undo.isNotEmpty()

    fun canRedo(): Boolean = redo.isNotEmpty()

    fun getCurrentBytes(): Long = currentBytes

    fun getMaxBytes(): Long = maxBytes

    /** Вернуть предыдущее состояние и положить текущее в redo. */
    fun undo(pcm: FloatArray, markers: List<Int>, exportIntervals: List<IntArray>?): Snapshot? {
        if (undo.isEmpty()) return null
        val prev = undo.pop()
        currentBytes -= prev.pcm.size.toLong() * 4
        val cur = Snapshot(pcm, markers, exportIntervals)
        redo.push(cur)
        return prev
    }

    fun redo(pcm: FloatArray, markers: List<Int>, exportIntervals: List<IntArray>?): Snapshot? {
        if (redo.isEmpty()) return null
        val next = redo.pop()
        val cur = Snapshot(pcm, markers, exportIntervals)
        undo.push(cur)
        currentBytes += cur.pcm.size.toLong() * 4
        return next
    }

    fun clear() {
        undo.clear()
        redo.clear()
        currentBytes = 0
    }
}

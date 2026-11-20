package com.grinya.voicesampleprep.model

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.Scanner
import java.util.regex.Pattern

class AudioMeta {

    @JvmField var sampleRate: Int = 0
    @JvmField var channels: Int = 0
    @JvmField var bits: Int = 0 // 0 = неизвестно (MP3, FLAC)
    @JvmField var format: String? = null // "wav", "mp3" и т.д.

    @JvmField var markers: MutableList<Int> = ArrayList()
    @JvmField var exportIntervals: MutableList<IntArray> = ArrayList()

    companion object {
        private const val TAG = "AudioMeta"
        private val INTERVAL_PATTERN = Pattern.compile("\\[(\\d+)\\s*,\\s*(\\d+)\\]")

        // ---------- Сохранение ----------

        /** Атомарная запись: пишем во временный .meta.tmp, затем rename. */
        @JvmStatic
        fun save(wavFile: File?, meta: AudioMeta) {
            if (wavFile == null) return
            val metaFile = File(wavFile.absolutePath + ".meta")
            val tmpFile = File(metaFile.absolutePath + ".tmp")
            try {
                FileWriter(tmpFile).use { fw ->
                    fw.write("{\n")
                    fw.write("  \"markers\": " + markersToJson(meta.markers) + ",\n")
                    fw.write("  \"exportIntervals\": " + intervalsToJson(meta.exportIntervals) + ",\n")
                    fw.write("  \"original\": {\n")
                    fw.write("    \"sampleRate\": " + meta.sampleRate + ",\n")
                    fw.write("    \"channels\": " + meta.channels + ",\n")
                    fw.write("    \"bits\": " + meta.bits + ",\n")
                    fw.write("    \"format\": \"" + escape(meta.format) + "\"\n")
                    fw.write("  }\n")
                    fw.write("}\n")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Save failed", e)
                tmpFile.delete()
                return
            }
            metaFile.delete()
            if (!tmpFile.renameTo(metaFile)) {
                Log.e(TAG, "Rename failed: $tmpFile -> $metaFile")
                tmpFile.delete()
            }
        }

        // ---------- Загрузка ----------

        @JvmStatic
        fun load(wavFile: File?): AudioMeta {
            val meta = AudioMeta()
            if (wavFile == null) return meta
            val metaFile = File(wavFile.absolutePath + ".meta")
            if (!metaFile.exists()) return meta

            try {
                val sb = StringBuilder()
                Scanner(metaFile).use { sc ->
                    while (sc.hasNextLine()) sb.append(sc.nextLine())
                }
                val json = sb.toString().trim()

                val mkStart = json.indexOf("\"markers\"")
                if (mkStart >= 0) {
                    val arrStart = json.indexOf("[", mkStart)
                    val arrEnd = if (arrStart >= 0) json.indexOf("]", arrStart) else -1
                    if (arrStart >= 0 && arrEnd > arrStart) {
                        val arr = json.substring(arrStart + 1, arrEnd).trim()
                        if (arr.isNotEmpty()) {
                            for (s in arr.split(",")) {
                                s.trim().toIntOrNull()?.let { meta.markers.add(it) }
                            }
                        }
                    }
                }

                val eiStart = json.indexOf("\"exportIntervals\"")
                if (eiStart >= 0) {
                    val arrStart = json.indexOf("[", eiStart)
                    if (arrStart >= 0) {
                        var depth = 0
                        var arrEnd = -1
                        for (i in arrStart until json.length) {
                            val ch = json[i]
                            if (ch == '[') {
                                depth++
                            } else if (ch == ']') {
                                depth--
                                if (depth == 0) {
                                    arrEnd = i
                                    break
                                }
                            }
                        }
                        if (arrEnd > arrStart) {
                            val arr = json.substring(arrStart, arrEnd + 1)
                            val m = INTERVAL_PATTERN.matcher(arr)
                            while (m.find()) {
                                val a = m.group(1)?.toIntOrNull()
                                val b = m.group(2)?.toIntOrNull()
                                if (a != null && b != null) meta.exportIntervals.add(intArrayOf(a, b))
                            }
                        }
                    }
                }

                meta.sampleRate = parseIntField(json, "sampleRate")
                meta.channels = parseIntField(json, "channels")
                meta.bits = parseIntField(json, "bits")
                meta.format = parseStringField(json, "format")
            } catch (e: Exception) {
                Log.e(TAG, "Load failed", e)
            }
            return meta
        }

        // ---------- Хелперы ----------

        private fun markersToJson(markers: List<Int>?): String =
            if (markers.isNullOrEmpty()) "[]" else markers.joinToString(", ", "[", "]")

        private fun intervalsToJson(intervals: List<IntArray>?): String =
            if (intervals.isNullOrEmpty()) "[]"
            else intervals.joinToString(", ", "[", "]") { "[${it[0]}, ${it[1]}]" }

        private fun parseIntField(json: String, key: String): Int {
            val idx = json.indexOf("\"$key\"")
            if (idx < 0) return 0
            val colon = json.indexOf(":", idx)
            if (colon < 0) return 0
            var end = colon + 1
            while (end < json.length) {
                val c = json[end]
                if (c == ',' || c == '}' || c == '\n') break
                end++
            }
            return json.substring(colon + 1, end).trim().toIntOrNull() ?: 0
        }

        private fun parseStringField(json: String, key: String): String {
            val idx = json.indexOf("\"$key\"")
            if (idx < 0) return ""
            val colon = json.indexOf(":", idx)
            if (colon < 0) return ""
            val q1 = json.indexOf("\"", colon + 1)
            if (q1 < 0) return ""
            val q2 = json.indexOf("\"", q1 + 1)
            if (q2 < 0) return ""
            return json.substring(q1 + 1, q2)
        }

        private fun escape(s: String?): String =
            s?.replace("\\", "\\\\")
                ?.replace("\"", "\\\"")
                ?.replace("\n", "\\n")
                ?.replace("\r", "\\r")
                ?: ""
    }
}

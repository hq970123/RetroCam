package com.hq.retrocam.gl

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.math.pow

/** A 3D LUT, r index fastest, values RGB in [0,1] (or the cube's own range). */
class CubeLut(
    val name: String,
    val size: Int,
    val data: FloatArray,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
)

/** How the camera image is turned into display colours before the look. */
data class ColorState(
    /** 0 = standard, 1 = camera delivers a log curve, 2 = simulated log. */
    val logIn: Int = 0,
    /** Convert log to display (built-in conversion). Ignored for logIn = 0. */
    val decode: Boolean = true,
    /** LUT strength, 0 = no LUT. */
    val lutMix: Float = 0f,
    /** Linear gain that undoes the log under-exposure. */
    val logGain: Float = LOG_GAIN,
) {
    /** True when this state changes pixels at all. */
    val active: Boolean get() = (logIn == 1 && decode) || (logIn == 2 && !decode) || lutMix > 0f

    companion object {
        /** -1.5 EV */
        const val LOG_GAIN = 2.828427f
        val NONE = ColorState()
    }
}

object CubeParser {
    fun parse(reader: BufferedReader, fallbackName: String): CubeLut {
        var size = 0
        var title: String? = null
        val dMin = floatArrayOf(0f, 0f, 0f)
        val dMax = floatArrayOf(1f, 1f, 1f)
        var data: FloatArray? = null
        var n = 0
        var lineNo = 0
        while (true) {
            val raw = reader.readLine() ?: break
            lineNo++
            val line = raw.trim()
            if (line.isEmpty() || line[0] == '#') continue
            val c = line[0]
            if (c.isLetter()) {
                val parts = line.split(Regex("\\s+"))
                when (parts[0].uppercase()) {
                    "TITLE" -> title = line.substringAfter("TITLE").trim().trim('"').ifBlank { null }
                    "LUT_3D_SIZE" -> {
                        size = parts.getOrNull(1)?.toIntOrNull() ?: throw IllegalArgumentException("LUT_3D_SIZE 无效")
                        require(size in 2..129) { "LUT 尺寸 $size 不支持" }
                        data = FloatArray(size * size * size * 3)
                    }
                    "LUT_1D_SIZE" -> throw IllegalArgumentException("暂不支持 1D LUT")
                    "DOMAIN_MIN" -> for (i in 0..2) dMin[i] = parts.getOrNull(i + 1)?.toFloatOrNull() ?: 0f
                    "DOMAIN_MAX" -> for (i in 0..2) dMax[i] = parts.getOrNull(i + 1)?.toFloatOrNull() ?: 1f
                    else -> {} // LUT_3D_INPUT_RANGE etc: ignore
                }
                continue
            }
            val d = data ?: throw IllegalArgumentException("缺少 LUT_3D_SIZE")
            if (n >= d.size) continue
            var i = 0
            var start = -1
            var k = 0
            val len = line.length
            while (i <= len && k < 3) {
                val ch = if (i < len) line[i] else ' '
                val ws = ch == ' ' || ch == '\t'
                if (!ws && start < 0) start = i
                if (ws && start >= 0) {
                    d[n + k] = line.substring(start, i).toFloatOrNull() ?: throw IllegalArgumentException("第 $lineNo 行数据错误")
                    k++
                    start = -1
                }
                i++
            }
            if (k < 3) throw IllegalArgumentException("第 $lineNo 行数据不足")
            n += 3
        }
        val d = data ?: throw IllegalArgumentException("不是有效的 .cube 文件")
        require(n == d.size) { "数据行数不对（${n / 3} / ${d.size / 3}）" }
        for (i in 0..2) require(dMax[i] > dMin[i]) { "DOMAIN 无效" }
        return CubeLut(title ?: fallbackName, size, d, dMin, dMax)
    }

    fun write(lut: CubeLut, file: File) {
        file.bufferedWriter().use { w ->
            w.write("TITLE \"${lut.name.replace("\"", "")}\"\n")
            w.write("LUT_3D_SIZE ${lut.size}\n")
            w.write("DOMAIN_MIN ${lut.domainMin.joinToString(" ")}\n")
            w.write("DOMAIN_MAX ${lut.domainMax.joinToString(" ")}\n")
            val d = lut.data
            var i = 0
            while (i < d.size) {
                w.write("%.6f %.6f %.6f\n".format(java.util.Locale.US, d[i], d[i + 1], d[i + 2]))
                i += 3
            }
        }
    }
}

/** Built-in creative LUTs, generated from colour functions (display-referred in, out). */
object BuiltinLuts {
    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t)
    }
    private fun sCurve(x: Float, k: Float): Float { val c = x.coerceIn(0f, 1f); return c + (c * c * (3 - 2 * c) - c) * k }

    private fun build(name: String, size: Int = 33, f: (FloatArray) -> Unit): CubeLut {
        val d = FloatArray(size * size * size * 3)
        val px = FloatArray(3)
        var o = 0
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            px[0] = r / (size - 1f); px[1] = g / (size - 1f); px[2] = b / (size - 1f)
            f(px)
            d[o] = px[0].coerceIn(0f, 1f); d[o + 1] = px[1].coerceIn(0f, 1f); d[o + 2] = px[2].coerceIn(0f, 1f)
            o += 3
        }
        return CubeLut(name, size, d)
    }

    private fun sat(p: FloatArray, s: Float) {
        val l = luma(p[0], p[1], p[2])
        for (i in 0..2) p[i] = l + (p[i] - l) * s
    }

    /** id -> (name, generator) */
    val all: List<Pair<String, String>> = listOf(
        "b:tealorange" to "青橙电影",
        "b:print2383" to "印片 2383",
        "b:faded" to "褪色胶片",
        "b:coldnight" to "冷调夜景",
        "b:goldenhour" to "黄金时刻",
    )

    fun make(id: String): CubeLut? = when (id) {
        "b:tealorange" -> build("青橙电影") { p ->
            val l = luma(p[0], p[1], p[2])
            // warm-ish (skin) hues get pushed to orange, everything else to teal
            val warm = smooth(0.0f, 0.12f, p[0] - p[2])
            val sh = 1f - smooth(0.0f, 0.6f, l)
            p[0] += (0.06f * warm - 0.05f * (1 - warm) * sh)
            p[1] += (0.015f * warm + 0.02f * (1 - warm) * sh)
            p[2] += (-0.05f * warm + 0.06f * (1 - warm) * sh)
            sat(p, 1.12f)
            for (i in 0..2) p[i] = sCurve(p[i], 0.35f)
        }
        "b:print2383" -> build("印片 2383") { p ->
            for (i in 0..2) p[i] = sCurve(p[i], 0.75f)
            val l = luma(p[0], p[1], p[2])
            val sh = 1f - smooth(0f, 0.45f, l)
            val hi = smooth(0.55f, 1f, l)
            p[0] += -0.02f * sh + 0.035f * hi
            p[1] += 0.01f * sh + 0.015f * hi
            p[2] += 0.025f * sh - 0.03f * hi
            // dense, slightly desaturated reds; toe stays deep
            sat(p, 0.9f)
            for (i in 0..2) p[i] = p[i] * 0.97f + 0.006f
        }
        "b:faded" -> build("褪色胶片") { p ->
            sat(p, 0.82f)
            for (i in 0..2) p[i] = 0.075f + p[i] * 0.86f
            p[0] += 0.02f; p[2] -= 0.015f
            val l = luma(p[0], p[1], p[2])
            p[1] += 0.012f * (1f - smooth(0f, 0.5f, l))
        }
        "b:coldnight" -> build("冷调夜景") { p ->
            val l = luma(p[0], p[1], p[2])
            p[0] = p[0] * 0.9f; p[2] = p[2] * 1.06f + 0.03f * (1 - l)
            p[1] = p[1] * 0.98f + 0.01f
            for (i in 0..2) p[i] = sCurve(p[i], 0.45f)
            sat(p, 0.92f)
            // keep warm practical lights warm
            val warm = smooth(0.5f, 0.9f, l) * smooth(0.05f, 0.2f, p[0] - p[2])
            p[0] += 0.05f * warm; p[1] += 0.02f * warm
        }
        "b:goldenhour" -> build("黄金时刻") { p ->
            val l = luma(p[0], p[1], p[2])
            p[0] += 0.05f * smooth(0.2f, 1f, l)
            p[1] += 0.02f * smooth(0.2f, 1f, l)
            p[2] -= 0.06f * smooth(0.1f, 1f, l)
            sat(p, 1.08f)
            for (i in 0..2) p[i] = 1f - (1f - p[i]).toDouble().pow(1.08).toFloat()
        }
        else -> null
    }
}

/** Built-in and imported LUTs. Imported files live in filesDir/luts. */
class LutLibrary(private val ctx: Context) {
    data class Entry(val id: String, val name: String, val builtin: Boolean)

    private val dir = File(ctx.filesDir, "luts").apply { mkdirs() }
    private val cache = HashMap<String, CubeLut>()

    fun entries(): List<Entry> {
        val list = BuiltinLuts.all.map { Entry(it.first, it.second, true) }.toMutableList()
        dir.listFiles { f -> f.name.endsWith(".cube", true) }?.sortedBy { it.lastModified() }?.forEach {
            list += Entry("f:" + it.name, it.name.removeSuffix(".cube").removeSuffix(".CUBE"), false)
        }
        return list
    }

    /** Loads (and caches) a LUT. Call off the main thread. */
    fun load(id: String): CubeLut? {
        cache[id]?.let { return it }
        val lut = if (id.startsWith("b:")) BuiltinLuts.make(id) else {
            val f = File(dir, id.removePrefix("f:"))
            if (!f.exists()) null else f.bufferedReader().use { CubeParser.parse(it, f.nameWithoutExtension) }
        }
        if (lut != null) {
            if (cache.size > 6) cache.clear()
            cache[id] = lut
        }
        return lut
    }

    /** Parses and copies a .cube file into the library. Returns the new entry id. Call off the main thread. */
    fun import(uri: Uri): Entry {
        var display = "LUT"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { display = it }
        }
        val base = display.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40).ifBlank { "LUT" }
        val lut = ctx.contentResolver.openInputStream(uri)?.use { s ->
            CubeParser.parse(BufferedReader(InputStreamReader(s), 1 shl 16), base)
        } ?: throw IllegalArgumentException("无法读取文件")
        var name = "$base.cube"
        var k = 2
        while (File(dir, name).exists()) { name = "$base ($k).cube"; k++ }
        val out = File(dir, name)
        ctx.contentResolver.openInputStream(uri)?.use { s -> out.outputStream().use { s.copyTo(it) } }
        val id = "f:$name"
        cache[id] = lut
        return Entry(id, name.removeSuffix(".cube"), false)
    }

    fun delete(id: String) {
        if (!id.startsWith("f:")) return
        File(dir, id.removePrefix("f:")).delete()
        cache.remove(id)
    }
}

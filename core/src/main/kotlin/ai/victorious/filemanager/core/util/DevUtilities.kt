package ai.victorious.filemanager.core.util

import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Hash calculator. */
object Hashes {
    val algorithms = listOf("MD5", "SHA-1", "SHA-256", "SHA-512")
    fun ofBytes(algorithm: String, data: ByteArray): String = MessageDigest.getInstance(algorithm).digest(data).toHex()
    fun ofText(algorithm: String, text: String): String = ofBytes(algorithm, text.toByteArray(Charsets.UTF_8))
    fun ofFile(algorithm: String, file: File): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().toHex()
    }
    /** Case-insensitive, whitespace-tolerant compare against an expected digest. */
    fun matches(actual: String, expected: String) = actual.equals(expected.trim().replace(" ", ""), ignoreCase = true)
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Base64 / hex encode-decode on UTF-8 text. Decoders throw IllegalArgumentException on bad input. */
object Codec {
    fun base64Encode(text: String, urlSafe: Boolean = false): String =
        (if (urlSafe) Base64.getUrlEncoder() else Base64.getEncoder()).encodeToString(text.toByteArray(Charsets.UTF_8))
    fun base64Decode(text: String): String {
        val t = text.trim().replace("\\s".toRegex(), "")
        val bytes = if (t.contains('-') || t.contains('_')) Base64.getUrlDecoder().decode(t) else Base64.getDecoder().decode(t)
        return String(bytes, Charsets.UTF_8)
    }
    fun hexEncode(text: String): String = text.toByteArray(Charsets.UTF_8).toHex()
    fun hexDecode(text: String): String {
        val t = text.replace("0x", "").replace("[\\s:,-]".toRegex(), "")
        require(t.length % 2 == 0) { "Odd number of hex digits" }
        require(t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "Not hex" }
        return String(ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, Charsets.UTF_8)
    }
}

/** Unix timestamp <-> date conversion. */
object Timestamps {
    data class Converted(val epochSeconds: Long, val epochMillis: Long, val utc: String, val local: String)

    /** Seconds when |n| < 1e11 (until year 5138), otherwise milliseconds. */
    fun fromNumber(n: Long, zone: ZoneId = ZoneId.systemDefault()): Converted {
        val ms = if (kotlin.math.abs(n) < 100_000_000_000L) n * 1000 else n
        return of(Instant.ofEpochMilli(ms), zone)
    }

    /** Accepts ISO-8601 instants ("2026-10-28T06:00:00Z"), offsets, or local "yyyy-MM-dd HH:mm:ss" in [zone]. */
    fun fromText(s: String, zone: ZoneId = ZoneId.systemDefault()): Converted {
        val t = s.trim()
        t.toLongOrNull()?.let { return fromNumber(it, zone) }
        val instant = runCatching { Instant.parse(t) }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(t).toInstant() }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(t).toInstant() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(t.replace(' ', 'T')).atZone(zone).toInstant() }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(t).atStartOfDay(zone).toInstant() }.getOrNull()
            ?: throw IllegalArgumentException("Unrecognised date: $s")
        return of(instant, zone)
    }

    private fun of(i: Instant, zone: ZoneId) = Converted(
        i.epochSecond, i.toEpochMilli(),
        DateTimeFormatter.ISO_INSTANT.format(i),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").format(i.atZone(zone)),
    )
}

/** Colour parsing / formatting. */
object Colors {
    data class Argb(val a: Int, val r: Int, val g: Int, val b: Int) {
        val argb: Int get() = (a shl 24) or (r shl 16) or (g shl 8) or b
        fun hex(): String = if (a == 255) "#%02X%02X%02X".format(r, g, b) else "#%02X%02X%02X%02X".format(a, r, g, b)
        fun rgb(): String = if (a == 255) "rgb($r, $g, $b)" else "rgba($r, $g, $b, ${"%.2f".format(java.util.Locale.ROOT, a / 255.0)})"
        fun hsv(): Triple<Float, Float, Float> {
            val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
            val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf); val d = max - min
            val h = when { d == 0f -> 0f; max == rf -> 60 * (((gf - bf) / d) % 6); max == gf -> 60 * ((bf - rf) / d + 2); else -> 60 * ((rf - gf) / d + 4) }
            return Triple(if (h < 0) h + 360 else h, if (max == 0f) 0f else d / max, max)
        }
    }

    fun parse(input: String): Argb {
        val s = input.trim().lowercase()
        Regex("rgba?\\(([^)]*)\\)").matchEntire(s)?.let { m ->
            val p = m.groupValues[1].split(',').map { it.trim() }
            require(p.size == 3 || p.size == 4) { "rgb() needs 3 or 4 values" }
            val c = p.take(3).map { it.toInt().also { v -> require(v in 0..255) { "0-255 expected" } } }
            val a = if (p.size == 4) (p[3].toDouble().coerceIn(0.0, 1.0) * 255).toInt() else 255
            return Argb(a, c[0], c[1], c[2])
        }
        val h = s.removePrefix("#").removePrefix("0x")
        require(h.all { it.isDigit() || it in 'a'..'f' }) { "Not a colour: $input" }
        fun x(i: Int, n: Int = 2) = h.substring(i, i + n).let { if (n == 1) (it + it) else it }.toInt(16)
        return when (h.length) {
            3 -> Argb(255, x(0, 1), x(1, 1), x(2, 1))
            4 -> Argb(x(0, 1), x(1, 1), x(2, 1), x(3, 1))
            6 -> Argb(255, x(0), x(2), x(4))
            8 -> Argb(x(0), x(2), x(4), x(6))
            else -> throw IllegalArgumentException("Not a colour: $input")
        }
    }
}

/** Regex tester. */
object RegexTester {
    data class Hit(val range: IntRange, val value: String, val groups: List<String?>)
    data class Result(val hits: List<Hit>, val error: String?, val replaced: String?)

    fun run(pattern: String, input: String, ignoreCase: Boolean = false, multiline: Boolean = false,
            dotAll: Boolean = false, replacement: String? = null, limit: Int = 1000): Result {
        val opts = buildSet {
            if (ignoreCase) add(RegexOption.IGNORE_CASE)
            if (multiline) add(RegexOption.MULTILINE)
            if (dotAll) add(RegexOption.DOT_MATCHES_ALL)
        }
        val re = try { Regex(pattern, opts) } catch (e: Exception) { return Result(emptyList(), e.message?.lineSequence()?.firstOrNull() ?: "Invalid regex", null) }
        val hits = re.findAll(input).take(limit).map { m -> Hit(m.range, m.value, m.groups.drop(1).map { it?.value }) }.toList()
        val replaced = replacement?.let { r -> runCatching { re.replace(input, r) }.getOrElse { return Result(hits, "Bad replacement: ${it.message}", null) } }
        return Result(hits, null, replaced)
    }
}

package com.alondra1.app.data.settings

import kotlin.math.max
import kotlin.math.roundToInt

// Workspace background, the same feature and defaults as Alondra1 for Windows (Settings → Background): a bundled
// picture or the user's own, with opacity, blur, desaturation, brightness, overlay, fit and position. Everything stays
// on the device; nothing is downloaded or uploaded.

enum class BackgroundFit(val label: String) { COVER("Cover"), CONTAIN("Contain") }

enum class BackgroundPosition(val label: String) { CENTER("Center"), TOP("Top"), BOTTOM("Bottom"), LEFT("Left"), RIGHT("Right") }

data class BackgroundSettings(
    val enabled: Boolean = true,
    /** A built-in id from [BackgroundCatalog], [BackgroundCatalog.CUSTOM] or [BackgroundCatalog.NONE]. */
    val image: String = BackgroundCatalog.DEFAULT,
    /** The app-managed copy of the user's picture (files/backgrounds). */
    val customPath: String? = null,
    val opacity: Float = DEFAULT_OPACITY,
    val blur: Float = DEFAULT_BLUR,
    val desaturation: Float = DEFAULT_DESATURATION,
    val brightness: Float = 0f,
    val overlay: Float = 0f,
    val fit: BackgroundFit = BackgroundFit.COVER,
    val position: BackgroundPosition = BackgroundPosition.CENTER,
) {
    /** Clamps every value into its range and replaces unknown picture ids with the default. */
    fun normalized(): BackgroundSettings {
        fun clamp(v: Float, min: Float, max: Float, fallback: Float) = if (v.isFinite()) v.coerceIn(min, max) else fallback
        var id = if (BackgroundCatalog.isKnown(image)) image else BackgroundCatalog.DEFAULT
        val path = customPath?.takeIf { it.isNotBlank() }
        if (id == BackgroundCatalog.CUSTOM && path == null) id = BackgroundCatalog.DEFAULT
        return copy(
            image = id,
            customPath = path,
            opacity = clamp(opacity, 0f, MAX_OPACITY, DEFAULT_OPACITY),
            blur = clamp(blur, 0f, MAX_BLUR, DEFAULT_BLUR),
            desaturation = clamp(desaturation, 0f, 1f, DEFAULT_DESATURATION),
            brightness = clamp(brightness, -0.5f, 0.5f, 0f),
            overlay = clamp(overlay, 0f, MAX_OVERLAY, 0f),
        )
    }

    /** Keeps the picture choice (and the user's own picture) and resets every adjustment. */
    fun resetAdjustments(): BackgroundSettings = BackgroundSettings(enabled = enabled, image = image, customPath = customPath)

    companion object {
        const val DEFAULT_OPACITY = 0.85f
        const val DEFAULT_BLUR = 2f
        const val DEFAULT_DESATURATION = 0.1f
        const val MAX_OPACITY = 1f
        const val MAX_BLUR = 40f
        const val MAX_OVERLAY = 0.8f
    }
}

object BackgroundCatalog {
    const val NONE = "none"
    const val CUSTOM = "custom"
    const val DEFAULT = "mountains"

    /** Built-in pictures: original artwork made for Alondra1 (alondra1-windows design/backgrounds). */
    val builtIn: List<Pair<String, String>> = listOf(
        "soft-blue" to "Soft blue",
        "gray-geometric" to "Gray geometric",
        "coast" to "Muted coast",
        "mountains" to "Misty mountains",
        "architecture" to "Minimal architecture",
        "warm-neutral" to "Warm neutral",
        "dark-blue" to "Dark blue",
        "ocean" to "Ocean",
    )

    fun isKnown(id: String?): Boolean = id == NONE || id == CUSTOM || builtIn.any { it.first == id }

    fun name(id: String): String = when (id) {
        NONE -> "Plain color"
        CUSTOM -> "My picture"
        else -> builtIn.firstOrNull { it.first == id }?.second ?: id
    }
}

/** Checks a picture chosen as a background by its content, not its name. */
object BackgroundImageFile {
    const val MAX_BYTES = 25L * 1024 * 1024

    enum class Kind { UNKNOWN, PNG, JPEG, WEBP }

    fun detect(header: ByteArray): Kind {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (header.size >= 8 && header.copyOfRange(0, 8).contentEquals(png)) return Kind.PNG
        if (header.size >= 3 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) return Kind.JPEG
        if (header.size >= 12 && String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP") return Kind.WEBP
        return Kind.UNKNOWN
    }
}

/**
 * Desaturates, adapts the picture's brightness to the theme, then applies the user's brightness (same method as
 * Windows). In the dark theme a light picture is darkened toward an average of 36%, in the light theme a dark picture
 * is lifted toward 72%, so text over it stays readable. Pixels are ARGB ints; the array is changed in place.
 */
object BackgroundTone {
    fun process(pixels: IntArray, desaturation: Float, brightness: Float, dark: Boolean) {
        val keep = 1.0 - desaturation
        val shift = brightness * 255.0
        var total = 0.0
        var samples = 0
        var i = 0
        while (i < pixels.size) {
            val p = pixels[i]
            total += 0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)
            samples++
            i += 16
        }
        val mean = if (samples == 0) 128.0 else total / samples
        val darkTarget = 0.36 * 255
        val lightTarget = 0.72 * 255
        val darken = if (dark && mean > darkTarget) darkTarget / mean else 1.0
        val lift = if (!dark && mean < lightTarget) (255 - lightTarget) / max(1.0, 255 - mean) else 1.0
        fun tone(v: Double) = if (lift < 1) 255 - (255 - v) * lift else v * darken
        fun byte(v: Double) = v.roundToInt().coerceIn(0, 255)
        for (j in pixels.indices) {
            val p = pixels[j]
            val a = p ushr 24
            val r = ((p shr 16) and 0xFF).toDouble()
            val g = ((p shr 8) and 0xFF).toDouble()
            val b = (p and 0xFF).toDouble()
            val gray = 0.2126 * r + 0.7152 * g + 0.0722 * b
            val nr = byte(tone(gray + (r - gray) * keep) + shift)
            val ng = byte(tone(gray + (g - gray) * keep) + shift)
            val nb = byte(tone(gray + (b - gray) * keep) + shift)
            pixels[j] = (a shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    /** Average luminance 0..255, for tests and diagnostics. */
    fun meanLuminance(pixels: IntArray): Double =
        if (pixels.isEmpty()) 0.0 else pixels.sumOf { p -> 0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF) } / pixels.size
}

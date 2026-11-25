package com.alondra1.app.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.alondra1.app.R
import com.alondra1.app.data.settings.BackgroundCatalog
import com.alondra1.app.data.settings.BackgroundFit
import com.alondra1.app.data.settings.BackgroundPosition
import com.alondra1.app.data.settings.BackgroundSettings
import com.alondra1.app.data.settings.BackgroundTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Whether these settings show a picture (a missing custom file counts as no picture). */
fun BackgroundSettings.showsPicture(): Boolean {
    val s = normalized()
    if (!s.enabled || s.image == BackgroundCatalog.NONE) return false
    return s.image != BackgroundCatalog.CUSTOM || s.customPath?.let { File(it).isFile } == true
}

/** Blur needs RenderEffect (Android 12); on older versions the picture is shown sharp. */
val blurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The workspace background: behind the screens, never inside dialogs, menus or text fields (those stay opaque). The
 * picture is decoded at about the screen size and toned for the theme off the main thread; blur runs on the GPU.
 */
@Composable
fun WorkspaceBackground(settings: BackgroundSettings, modifier: Modifier = Modifier) {
    val s = settings.normalized()
    if (!s.showsPicture()) return
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val config = LocalConfiguration.current
    val target = (max(config.screenWidthDp, config.screenHeightDp) * context.resources.displayMetrics.density).toInt().coerceIn(720, 2560)
    val key = listOf(s.image, s.customPath, s.desaturation, s.brightness, dark, target)
    val picture by produceState<ImageBitmap?>(null, key) {
        value = withContext(Dispatchers.Default) { runCatching { BackgroundPictures.processed(context, s, dark, target) }.getOrNull() }
    }

    val image = picture ?: return
    Box(modifier.fillMaxSize().clearAndSetSemantics { }) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = if (s.fit == BackgroundFit.COVER) ContentScale.Crop else ContentScale.Fit,
            alignment = when (s.position) {
                BackgroundPosition.TOP -> Alignment.TopCenter
                BackgroundPosition.BOTTOM -> Alignment.BottomCenter
                BackgroundPosition.LEFT -> Alignment.CenterStart
                BackgroundPosition.RIGHT -> Alignment.CenterEnd
                BackgroundPosition.CENTER -> Alignment.Center
            },
            modifier = Modifier.fillMaxSize().alpha(s.opacity).let { if (s.blur > 0.5f && blurSupported) it.blur(s.blur.dp) else it },
        )
        if (s.overlay > 0.001f) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = s.overlay)))
        }
    }
}

object BackgroundPictures {
    @DrawableRes
    fun resource(id: String): Int? = when (id) {
        "soft-blue" -> R.drawable.bg_soft_blue
        "gray-geometric" -> R.drawable.bg_gray_geometric
        "coast" -> R.drawable.bg_coast
        "mountains" -> R.drawable.bg_mountains
        "architecture" -> R.drawable.bg_architecture
        "warm-neutral" -> R.drawable.bg_warm_neutral
        "dark-blue" -> R.drawable.bg_dark_blue
        "ocean" -> R.drawable.bg_ocean
        else -> null
    }

    /** The picture of [id] (or the custom file) decoded to about [longEdge] pixels, or null when it can't be read. */
    fun decode(context: Context, id: String, customPath: String?, longEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val res = resource(id)
        when {
            res != null -> BitmapFactory.decodeResource(context.resources, res, bounds)
            id == BackgroundCatalog.CUSTOM && customPath != null && File(customPath).isFile -> BitmapFactory.decodeFile(customPath, bounds)
            else -> return null
        }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longEdge) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inMutable = true }
        return if (res != null) BitmapFactory.decodeResource(context.resources, res, options) else BitmapFactory.decodeFile(customPath, options)
    }

    fun processed(context: Context, s: BackgroundSettings, dark: Boolean, longEdge: Int): ImageBitmap? {
        val bitmap = decode(context, s.image, s.customPath, longEdge) ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        BackgroundTone.process(pixels, s.desaturation, s.brightness, dark)
        val out = Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        bitmap.recycle()
        return out.asImageBitmap()
    }
}

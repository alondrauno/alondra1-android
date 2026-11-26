package com.alondra1.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.alondra1.app.data.settings.BackgroundCatalog
import com.alondra1.app.data.settings.BackgroundFit
import com.alondra1.app.data.settings.BackgroundImageFile
import com.alondra1.app.data.settings.BackgroundPosition
import com.alondra1.app.data.settings.BackgroundSettings
import com.alondra1.app.data.settings.BackgroundStore
import com.alondra1.app.data.settings.BackgroundTone
import com.alondra1.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Workspace background: the same rules and defaults as Alondra1 for Windows. */
class BackgroundTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun defaultsAreThePlainBackgroundWithWindowsAdjustments() {
        val s = BackgroundSettings()
        assertTrue(s.enabled)
        // Android UI spec §3.3: no picture behind the primary screens unless the user picks one.
        assertEquals(BackgroundCatalog.NONE, s.image)
        assertEquals(0.85f, s.opacity)
        assertEquals(2f, s.blur)
        assertEquals(0.1f, s.desaturation)
        assertEquals(BackgroundFit.COVER, s.fit)
        assertEquals(BackgroundPosition.CENTER, s.position)
    }

    @Test fun normalizeClampsAndRepairs() {
        val s = BackgroundSettings(image = "unknown", opacity = 3f, blur = -1f, desaturation = Float.NaN, brightness = 2f, overlay = 1f).normalized()
        assertEquals(BackgroundCatalog.DEFAULT, s.image)
        assertEquals(1f, s.opacity)
        assertEquals(0f, s.blur)
        assertEquals(BackgroundSettings.DEFAULT_DESATURATION, s.desaturation)
        assertEquals(0.5f, s.brightness)
        assertEquals(BackgroundSettings.MAX_OVERLAY, s.overlay)
        // "My picture" without a stored file falls back to the default picture.
        assertEquals(BackgroundCatalog.DEFAULT, BackgroundSettings(image = BackgroundCatalog.CUSTOM, customPath = " ").normalized().image)
    }

    @Test fun resetKeepsThePictureChoice() {
        val s = BackgroundSettings(image = "ocean", customPath = "/x.jpg", opacity = 0.2f, overlay = 0.5f).resetAdjustments()
        assertEquals("ocean", s.image)
        assertEquals("/x.jpg", s.customPath)
        assertEquals(BackgroundSettings.DEFAULT_OPACITY, s.opacity)
        assertEquals(0f, s.overlay)
    }

    @Test fun detectsPicturesByContent() {
        assertEquals(BackgroundImageFile.Kind.PNG, BackgroundImageFile.detect(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(BackgroundImageFile.Kind.JPEG, BackgroundImageFile.detect(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals(BackgroundImageFile.Kind.WEBP, BackgroundImageFile.detect("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.US_ASCII)))
        assertEquals(BackgroundImageFile.Kind.UNKNOWN, BackgroundImageFile.detect("GIF89a".toByteArray()))
        assertEquals(BackgroundImageFile.Kind.UNKNOWN, BackgroundImageFile.detect(ByteArray(0)))
    }

    @Test fun darkThemeDarkensALightPictureTowardTheTarget() {
        val pixels = IntArray(64) { 0xFFE0E0E0.toInt() }
        BackgroundTone.process(pixels, desaturation = 0f, brightness = 0f, dark = true)
        assertEquals(0.36 * 255, BackgroundTone.meanLuminance(pixels), 1.5)
        assertEquals(0xFF, pixels[0] ushr 24) // alpha kept
    }

    @Test fun lightThemeLiftsADarkPictureAndKeepsALightOne() {
        val dark = IntArray(64) { 0xFF202020.toInt() }
        BackgroundTone.process(dark, 0f, 0f, dark = false)
        assertEquals(0.72 * 255, BackgroundTone.meanLuminance(dark), 1.5)
        val light = IntArray(64) { 0xFFF0F0F0.toInt() }
        BackgroundTone.process(light, 0f, 0f, dark = false)
        assertEquals(0xFFF0F0F0.toInt(), light[0])
    }

    @Test fun fullDesaturationMakesGray() {
        val pixels = IntArray(32) { 0xFFFF0000.toInt() }
        BackgroundTone.process(pixels, desaturation = 1f, brightness = 0f, dark = false)
        val p = pixels[0]
        assertEquals((p shr 16) and 0xFF, (p shr 8) and 0xFF)
        assertEquals((p shr 8) and 0xFF, p and 0xFF)
    }

    @Test fun settingsAreSavedAndRead() = runBlocking {
        val repo = SettingsRepository(PreferenceDataStoreFactory.create { tmp.newFile("bg.preferences_pb") })
        assertEquals(BackgroundSettings(), repo.settings.first().background)
        val chosen = BackgroundSettings(enabled = false, image = "coast", opacity = 0.5f, blur = 10f, desaturation = 0.3f, brightness = -0.2f,
            overlay = 0.4f, fit = BackgroundFit.CONTAIN, position = BackgroundPosition.TOP)
        repo.setBackground(chosen)
        assertEquals(chosen, repo.settings.first().background)
    }

    @Test fun storeDeletesOnlyItsOwnFiles() {
        val dir = tmp.newFolder("backgrounds")
        val store = BackgroundStore(dir)
        val own = java.io.File(dir, "custom-a.jpg").apply { writeText("x") }
        val outside = tmp.newFile("elsewhere.jpg")
        store.delete(outside.path)
        store.delete(java.io.File(dir, "../elsewhere.jpg").path)
        assertTrue(outside.exists())
        store.delete(own.path)
        assertFalse(own.exists())
        store.delete(null)
        assertNull(dir.listFiles()?.firstOrNull())
    }
}

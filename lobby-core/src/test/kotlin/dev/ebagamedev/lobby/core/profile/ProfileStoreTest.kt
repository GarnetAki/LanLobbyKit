package dev.ebagamedev.lobby.core.profile

import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Random
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileStoreTest {

    private fun pngBytes(width: Int, height: Int, noise: Boolean = false): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val rnd = Random(42)
        if (noise) {
            // блочный шум 2x2: после smooth-ресайза вдвое остаётся несжимаемым
            for (by in 0 until height / 2) for (bx in 0 until width / 2) {
                val c = rnd.nextInt()
                for (dy in 0..1) for (dx in 0..1) img.setRGB(bx * 2 + dx, by * 2 + dy, c)
            }
        } else {
            for (y in 0 until height) for (x in 0 until width) img.setRGB(x, y, 0xFFFF5722.toInt())
        }
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    @Test
    fun `first load creates persistent profileId`(@TempDir dir: Path) {
        val store = ProfileStore("test", dir)
        val first = store.loadOrCreate()
        val second = ProfileStore("test", dir).loadOrCreate()
        assertEquals(first.profileId, second.profileId)
        assertTrue(first.profileId.isNotBlank())
        assertTrue(dir.resolve("profile.json").exists())
    }

    @Test
    fun `save and load roundtrip`(@TempDir dir: Path) {
        val store = ProfileStore("test", dir)
        val profile = store.loadOrCreate().copy(nick = "X", lastHost = "1.2.3.4", lastPort = 9000)
        store.save(profile)
        assertEquals(profile, ProfileStore("test", dir).loadOrCreate())
    }

    @Test
    fun `avatar saved resized to 128 and readable`(@TempDir dir: Path) {
        val store = ProfileStore("test", dir)
        store.saveAvatarPng(pngBytes(500, 300))
        val loaded = store.loadAvatarPng()
        assertNotNull(loaded)
        val img = ImageIO.read(loaded.inputStream())
        assertEquals(128, img.width)
        assertEquals(128, img.height)
        assertTrue(loaded.size <= 64 * 1024)
    }

    @Test
    fun `avatar over 64kb rejected`(@TempDir dir: Path) {
        val store = ProfileStore("test", dir)
        // блочный шум 256x256: после ресайза в 128x128 всё ещё > 64 КБ
        assertFailsWith<IllegalArgumentException> {
            store.saveAvatarPng(pngBytes(256, 256, noise = true))
        }
    }

    @Test
    fun `avatarBase64 is null without avatar`(@TempDir dir: Path) {
        assertNull(ProfileStore("test", dir).avatarBase64())
    }

    @Test
    fun `avatarBase64 roundtrip`(@TempDir dir: Path) {
        val store = ProfileStore("test", dir)
        store.saveAvatarPng(pngBytes(64, 64))
        val b64 = store.avatarBase64()
        assertNotNull(b64)
        val decoded = java.util.Base64.getDecoder().decode(b64)
        assertEquals(store.loadAvatarPng()!!.toList(), decoded.toList())
    }
}

package dev.ebagamedev.lobby.core.profile

import dev.ebagamedev.lobby.core.protocol.LobbyJson
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

class ProfileStore(
    private val appId: String,
    private val dir: Path = Path.of(System.getProperty("user.home"), ".$appId"),
) {

    private val profileFile: Path get() = dir.resolve("profile.json")
    private val avatarFile: Path get() = dir.resolve("avatar.png")

    fun loadOrCreate(): PlayerProfile {
        if (profileFile.exists()) {
            return LobbyJson.decodeFromString(PlayerProfile.serializer(), profileFile.toFile().readText())
        }
        val profile = PlayerProfile(profileId = UUID.randomUUID().toString(), nick = "Player")
        save(profile)
        return profile
    }

    fun save(profile: PlayerProfile) {
        dir.toFile().mkdirs()
        profileFile.writeText(LobbyJson.encodeToString(PlayerProfile.serializer(), profile))
    }

    fun loadAvatarPng(): ByteArray? =
        if (avatarFile.exists()) avatarFile.readBytes() else null

    /** Ресайз до 128x128 PNG; бросает IllegalArgumentException, если результат > 64 КБ. */
    fun saveAvatarPng(source: ByteArray) {
        val decoded = ImageIO.read(source.inputStream())
            ?: throw IllegalArgumentException("not an image")
        val scaled = BufferedImage(AVATAR_SIZE, AVATAR_SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        g.drawImage(decoded.getScaledInstance(AVATAR_SIZE, AVATAR_SIZE, Image.SCALE_SMOOTH), 0, 0, null)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(scaled, "png", out)
        val bytes = out.toByteArray()
        require(bytes.size <= MAX_AVATAR_BYTES) { "avatar too large: ${bytes.size} bytes > $MAX_AVATAR_BYTES" }
        dir.toFile().mkdirs()
        avatarFile.writeBytes(bytes)
    }

    fun avatarBase64(): String? = loadAvatarPng()?.let { Base64.getEncoder().encodeToString(it) }

    companion object {
        const val AVATAR_SIZE = 128
        const val MAX_AVATAR_BYTES = 64 * 1024
    }
}

package com.translator.app.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/**
 * AES-256-GCM шифрованный сериализатор AppSettings.
 *
 * Формат файла:
 *   [1 byte magic = 0x47]
 *   [1 byte version = 0x01]
 *   [12 bytes IV]
 *   [N bytes ciphertext + 16 bytes GCM tag (встроен в ciphertext в Java AES/GCM)]
 *
 * Ключ создаётся один раз в Android Keystore (StrongBox если доступен) и никогда
 * не покидает Secure Enclave. Если файл повреждён или ключ сменился — DataStore
 * вернёт defaultValue (свежий AppSettings()).
 */
class AppSettingsSerializer @Inject constructor() : Serializer<AppSettings> {

    override val defaultValue: AppSettings = AppSettings()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun readFrom(input: InputStream): AppSettings {
        return try {
            val all = input.readBytes()
            if (all.isEmpty()) return defaultValue

            // Backward-compat: старый plain-JSON формат — пытаемся прочитать как есть.
            if (all[0].toInt() != MAGIC.toInt()) {
                return runCatching {
                    json.decodeFromString(AppSettings.serializer(), all.decodeToString())
                }.getOrDefault(defaultValue)
            }

            if (all.size < HEADER_SIZE) return defaultValue
            val version = all[1].toInt()
            if (version != VERSION) return defaultValue

            val iv = all.copyOfRange(2, 2 + IV_LENGTH)
            val ciphertext = all.copyOfRange(HEADER_SIZE, all.size)

            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            val plain = cipher.doFinal(ciphertext).decodeToString()
            json.decodeFromString(AppSettings.serializer(), plain)
        } catch (_: CorruptionException) {
            defaultValue
        } catch (_: Throwable) {
            defaultValue
        }
    }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) {
        val plain = json.encodeToString(AppSettings.serializer(), t).encodeToByteArray()
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)

        val out = ByteArrayOutputStream(HEADER_SIZE + ciphertext.size).apply {
            write(MAGIC.toInt())
            write(VERSION)
            write(iv)
            write(ciphertext)
        }
        output.write(out.toByteArray())
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)

        // StrongBox если доступен (Pixel 3+, Samsung S-series, etc.)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching { builder.setIsStrongBoxBacked(true) }
        }
        runCatching { gen.init(builder.build()) }.onFailure {
            // Откат на TEE если StrongBox не сработал.
            gen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }
        return gen.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "translator_settings_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val VERSION = 0x01
        private const val MAGIC: Byte = 0x47   // 'G' для Gemini
        private const val HEADER_SIZE = 2 + IV_LENGTH
    }
}
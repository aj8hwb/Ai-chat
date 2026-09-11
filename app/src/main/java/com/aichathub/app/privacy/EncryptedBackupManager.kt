package com.aichathub.app.privacy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted backup utility supporting two encryption modes:
 *
 * 1. Keystore-based (Android Keystore):
 *    - Hardware-backed key on many devices
 *    - Fast, no password required
 *    - Device-local (cannot transfer to other devices)
 *
 * 2. Password-based (cross-device):
 *    - User-provided password
 *    - PBKDF2 key derivation (256-bit key)
 *    - AES-256-GCM encryption
 *    - Portable across devices
 *
 * Backup format (.aichathub):
 *  - 4 bytes: magic "AICH"
 *  - 4 bytes: version
 *  - 1 byte: mode (0=keystore, 1=password)
 *  - For keystore mode:
 *    - 12 bytes: IV
 *    - N bytes: encrypted data
 *  - For password mode:
 *    - 32 bytes: salt
 *    - 12 bytes: IV
 *    - N bytes: encrypted data
 *  - 16 bytes: GCM auth tag
 *
 * Uses streaming encryption/decryption via CipherInputStream/CipherOutputStream
 * to avoid loading entire files into memory.
 */
object EncryptedBackupManager {

    private const val TAG = "EncryptedBackup"
    private const val KEYSTORE_ALIAS = "aichathub_backup_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val SALT_LENGTH = 32
    /**
     * PBKDF2 iteration count. 600,000 is the OWASP 2023 recommendation for
     * PBKDF2-HMAC-SHA256 at 256-bit key length. This provides meaningful
     * resistance against offline brute-force attacks on leaked backup files.
     */
    private const val PBKDF2_ITERATIONS = 600_000
    private const val KEY_LENGTH = 256
    private val MAGIC = "AICH".toByteArray()

    /** Buffer size for streaming reads/writes (256 KB). */
    private const val STREAM_BUFFER_SIZE = 256 * 1024

    /**
     * Checks if Android Keystore is available and the key exists.
     */
    fun isKeystoreAvailable(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.containsAlias(KEYSTORE_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "Keystore not available: ${e.message}")
            false
        }
    }

    /**
     * Generates or retrieves the encryption key from Android Keystore.
     */
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        val existingKey = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingKey != null) {
            return existingKey.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    /**
     * Derives a 256-bit key from a password using PBKDF2.
     */
    private fun deriveKeyFromPassword(password: String, salt: ByteArray): SecretKey {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    /**
     * Encrypts a file using AES-256-GCM with Keystore key (streaming).
     */
    fun encryptFile(
        context: Context,
        inputFile: File,
        outputFile: File
    ): Boolean {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv

            FileOutputStream(outputFile).use { out ->
                out.write(MAGIC)
                out.write(intToBytes(1)) // version 1
                out.write(0) // mode 0 = keystore
                out.write(iv)

                CipherOutputStream(out, cipher).use { cipherOut ->
                    FileInputStream(inputFile).use { inp ->
                        val buf = ByteArray(STREAM_BUFFER_SIZE)
                        var read: Int
                        while (inp.read(buf).also { read = it } != -1) {
                            cipherOut.write(buf, 0, read)
                        }
                    }
                }
            }

            Log.i(TAG, "File encrypted (keystore): ${inputFile.name} -> ${outputFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed: ${e.message}", e)
            false
        }
    }

    /**
     * Encrypts a file using AES-256-GCM with a password (cross-device portable, streaming).
     */
    fun encryptFileWithPassword(
        context: Context,
        inputFile: File,
        outputFile: File,
        password: String
    ): Boolean {
        return try {
            val salt = ByteArray(SALT_LENGTH)
            SecureRandom().nextBytes(salt)

            val key = deriveKeyFromPassword(password, salt)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv

            FileOutputStream(outputFile).use { out ->
                out.write(MAGIC)
                out.write(intToBytes(2)) // version 2
                out.write(1) // mode 1 = password
                out.write(salt)
                out.write(iv)

                CipherOutputStream(out, cipher).use { cipherOut ->
                    FileInputStream(inputFile).use { inp ->
                        val buf = ByteArray(STREAM_BUFFER_SIZE)
                        var read: Int
                        while (inp.read(buf).also { read = it } != -1) {
                            cipherOut.write(buf, 0, read)
                        }
                    }
                }
            }

            Log.i(TAG, "File encrypted (password): ${inputFile.name} -> ${outputFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Password encryption failed: ${e.message}", e)
            false
        }
    }

    /**
     * Decrypts a .aichathub file using AES-256-GCM (streaming).
     * Automatically detects whether keystore or password mode was used.
     */
    fun decryptFile(
        context: Context,
        inputFile: File,
        outputFile: File,
        password: String? = null
    ): Boolean {
        return try {
            FileInputStream(inputFile).use { inp ->
                // Read header fields (small, sequential)
                val magic = ByteArray(MAGIC.size)
                inp.read(magic)
                if (!magic.contentEquals(MAGIC)) {
                    Log.e(TAG, "Invalid magic bytes")
                    return false
                }

                val versionBytes = ByteArray(4)
                inp.read(versionBytes)
                val version = bytesToInt(versionBytes, 0)
                if (version < 1 || version > 2) {
                    Log.e(TAG, "Unsupported version: $version")
                    return false
                }

                val modeByte = ByteArray(1)
                inp.read(modeByte)
                val mode = modeByte[0].toInt()

                when (mode) {
                    0 -> {
                        // Keystore mode
                        if (password != null) {
                            Log.w(TAG, "Password provided for keystore-encrypted backup — ignoring password")
                        }
                        val key = getOrCreateKey()
                        val iv = ByteArray(GCM_IV_LENGTH)
                        inp.read(iv)

                        val cipher = Cipher.getInstance(AES_GCM)
                        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                        cipher.init(Cipher.DECRYPT_MODE, key, spec)

                        CipherInputStream(inp, cipher).use { cipherIn ->
                            FileOutputStream(outputFile).use { out ->
                                val buf = ByteArray(STREAM_BUFFER_SIZE)
                                var read: Int
                                while (cipherIn.read(buf).also { read = it } != -1) {
                                    out.write(buf, 0, read)
                                }
                            }
                        }
                    }
                    1 -> {
                        // Password mode
                        if (password == null) {
                            Log.e(TAG, "Password required for password-encrypted backup")
                            return false
                        }
                        val salt = ByteArray(SALT_LENGTH)
                        inp.read(salt)
                        val iv = ByteArray(GCM_IV_LENGTH)
                        inp.read(iv)

                        val key = deriveKeyFromPassword(password, salt)
                        val cipher = Cipher.getInstance(AES_GCM)
                        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                        cipher.init(Cipher.DECRYPT_MODE, key, spec)

                        CipherInputStream(inp, cipher).use { cipherIn ->
                            FileOutputStream(outputFile).use { out ->
                                val buf = ByteArray(STREAM_BUFFER_SIZE)
                                var read: Int
                                while (cipherIn.read(buf).also { read = it } != -1) {
                                    out.write(buf, 0, read)
                                }
                            }
                        }
                    }
                    else -> {
                        Log.e(TAG, "Unknown encryption mode: $mode")
                        return false
                    }
                }
            }

            Log.i(TAG, "File decrypted: ${inputFile.name} -> ${outputFile.name}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed: ${e.message}", e)
            false
        }
    }

    /**
     * Exports conversation data as an encrypted backup.
     */
    fun exportEncryptedBackup(
        context: Context,
        data: String,
        outputFile: File,
        password: String? = null
    ): Boolean {
        val tempFile = File(context.cacheDir, "backup_temp.json")
        return try {
            tempFile.writeText(data)
            if (password != null) {
                encryptFileWithPassword(context, tempFile, outputFile, password)
            } else {
                encryptFile(context, tempFile, outputFile)
            }
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Imports an encrypted backup.
     */
    fun importEncryptedBackup(
        context: Context,
        inputFile: File,
        password: String? = null
    ): String? {
        val tempFile = File(context.cacheDir, "import_temp.json")
        return try {
            if (decryptFile(context, inputFile, tempFile, password)) {
                tempFile.readText()
            } else null
        } finally {
            tempFile.delete()
        }
    }

    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    private fun bytesToInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}

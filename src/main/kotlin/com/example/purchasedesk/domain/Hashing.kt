package com.example.purchasedesk.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.KeySpec
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PBKDF2-HMAC-SHA256 password hashing with a per-user random salt. */
object Hashing {
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val KEY_BYTES = 32

    private val random = SecureRandom()

    /** Returns "iterations:saltBase64:hashBase64". */
    fun hashPassword(password: String): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val hash = pbkdf2(password, salt, ITERATIONS)
        return "$ITERATIONS:${b64(salt)}:${b64(hash)}"
    }

    /** Constant-time compare of a candidate password against a stored hash. */
    fun verify(password: String, stored: String): Boolean {
        val parts = stored.split(":")
        if (parts.size != 3) return false
        val iterations = parts[0].toIntOrNull() ?: return false
        return try {
            val salt = b64decode(parts[1])
            val expected = b64decode(parts[2])
            val actual = pbkdf2(password, salt, iterations)
            MessageDigest.isEqual(expected, actual)
        } catch (_: Exception) {
            false
        }
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BYTES * 8)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    private fun b64decode(s: String): ByteArray = Base64.getDecoder().decode(s)
}

/** Cryptographically-random, URL-safe token generator. */
object Tokens {
    private val random = SecureRandom()

    fun randomToken(bytes: Int = 32): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}

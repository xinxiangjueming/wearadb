package com.wearadb.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.interfaces.RSAPublicKey

/**
 * ADB AUTH RSAPUBLICKEY 载荷编码。
 *
 * adbd 要求公钥格式为：base64(Android 自定义 RSAPublicKey 结构体) + " " + 设备名 + "\0"，
 * 结构体（小端）为 libcrypto_utils/android_pubkey.cpp 定义的：
 *   uint32_t modulus_size_words; uint32_t n0inv;
 *   uint8_t modulus[256]; uint8_t rr[256]; uint32_t exponent;
 * 注意：不是 X.509 SubjectPublicKeyInfo DER。
 *
 * 移植自 libadb-android 3.1.1 的 io.github.muntashirakon.adb.AndroidPubkey
 * (SPDX-License-Identifier: GPL-3.0-or-later OR Apache-2.0)，原类为 package-private 无法直接引用。
 */
object AndroidPubkeyCodec {
    private const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8
    private const val ANDROID_PUBKEY_ENCODED_SIZE = 3 * 4 + 2 * ANDROID_PUBKEY_MODULUS_SIZE
    private const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4

    /** 生成 AUTH RSAPUBLICKEY 消息的完整载荷（含设备名与 NUL 结尾）。 */
    fun encodeWithName(publicKey: RSAPublicKey, name: String): ByteArray {
        val b64 = android.util.Base64.encodeToString(encode(publicKey), android.util.Base64.NO_WRAP)
        return (b64 + " " + name + "\u0000").toByteArray(Charsets.UTF_8)
    }

    private fun encode(publicKey: RSAPublicKey): ByteArray {
        require(publicKey.modulus.toByteArray().size >= ANDROID_PUBKEY_MODULUS_SIZE) {
            "Invalid key length ${publicKey.modulus.toByteArray().size}"
        }

        val keyStruct = ByteBuffer.allocate(ANDROID_PUBKEY_ENCODED_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        // modulus_size_words
        keyStruct.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)

        // n0inv = 2^32 - (N mod 2^32)^-1 mod 2^32（Montgomery 参数）
        val r32 = BigInteger.ZERO.setBit(32)
        var n0inv = publicKey.modulus.mod(r32)
        n0inv = n0inv.modInverse(r32)
        n0inv = r32.subtract(n0inv)
        keyStruct.putInt(n0inv.toInt())

        // modulus（小端）
        keyStruct.put(bigEndianToLittleEndianPadded(ANDROID_PUBKEY_MODULUS_SIZE, publicKey.modulus))

        // rr = 2^(rsa_size)^2 mod N（Montgomery 参数 R^2）
        var rr = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
        rr = rr.modPow(BigInteger.valueOf(2), publicKey.modulus)
        keyStruct.put(bigEndianToLittleEndianPadded(ANDROID_PUBKEY_MODULUS_SIZE, rr))

        // exponent
        keyStruct.putInt(publicKey.publicExponent.toInt())

        return keyStruct.array()
    }

    private fun bigEndianToLittleEndianPadded(len: Int, input: BigInteger): ByteArray {
        val out = ByteArray(len)
        val bytes = swapEndianness(input.toByteArray())
        val numBytes = minOf(bytes.size, len)
        System.arraycopy(bytes, 0, out, 0, numBytes)
        return out
    }

    private fun swapEndianness(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) out[i] = bytes[bytes.size - 1 - i]
        return out
    }
}

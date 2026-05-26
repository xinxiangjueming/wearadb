package com.wearadb.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * ADB 连接管理器，基于 libadb-android 库。
 * 负责 RSA 密钥对的生成/持久化，以及提供给 AbsAdbConnectionManager。
 */
class WearAdbConnectionManager private constructor(
    private val context: Context
) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)

        val (pk, cert) = loadOrGenerateKeyPair(context)
        privateKey = pk
        certificate = cert
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "wear-adb"

    companion object {
        @Volatile
        private var INSTANCE: WearAdbConnectionManager? = null

        fun getInstance(context: Context): WearAdbConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WearAdbConnectionManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        /**
         * 加载已保存的密钥对，不存在则生成新的并保存。
         */
        private fun loadOrGenerateKeyPair(context: Context): Pair<PrivateKey, Certificate> {
            val privKey = readPrivateKey(context)
            val cert = readCertificate(context)
            if (privKey != null && cert != null) {
                return privKey to cert
            }

            // 生成新的 RSA 2048 密钥对
            val keyPairGen = KeyPairGenerator.getInstance("RSA")
            keyPairGen.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            val keyPair: KeyPair = keyPairGen.generateKeyPair()
            val publicKey: PublicKey = keyPair.public
            val newPrivKey: PrivateKey = keyPair.private

            // 生成自签名证书
            val newCert = generateSelfSignedCert(publicKey, newPrivKey)

            // 持久化
            writePrivateKey(context, newPrivKey)
            writeCertificate(context, newCert)

            return newPrivKey to newCert
        }

        private fun generateSelfSignedCert(publicKey: PublicKey, privateKey: PrivateKey): Certificate {
            val subject = X500Principal("CN=wear-adb")
            val serial = BigInteger.ONE
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000)

            val builder = JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, publicKey
            )
            val signer = JcaContentSignerBuilder("SHA512withRSA").build(privateKey)
            val holder = builder.build(signer)
            return JcaX509CertificateConverter().getCertificate(holder)
        }

        private fun readPrivateKey(context: Context): PrivateKey? {
            val file = File(context.filesDir, "adb_private.key")
            if (!file.exists()) return null
            return try {
                val bytes = file.readBytes()
                val keyFactory = KeyFactory.getInstance("RSA")
                keyFactory.generatePrivate(PKCS8EncodedKeySpec(bytes))
            } catch (e: Exception) {
                null
            }
        }

        private fun writePrivateKey(context: Context, privateKey: PrivateKey) {
            val file = File(context.filesDir, "adb_private.key")
            FileOutputStream(file).use { it.write(privateKey.encoded) }
        }

        private fun readCertificate(context: Context): Certificate? {
            val file = File(context.filesDir, "adb_cert.pem")
            if (!file.exists()) return null
            return try {
                FileInputStream(file).use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it)
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun writeCertificate(context: Context, certificate: Certificate) {
            val file = File(context.filesDir, "adb_cert.pem")
            FileOutputStream(file).use { os ->
                os.write("-----BEGIN CERTIFICATE-----\n".toByteArray(StandardCharsets.UTF_8))
                os.write(android.util.Base64.encode(certificate.encoded, android.util.Base64.DEFAULT))
                os.write("-----END CERTIFICATE-----\n".toByteArray(StandardCharsets.UTF_8))
            }
        }
    }
}

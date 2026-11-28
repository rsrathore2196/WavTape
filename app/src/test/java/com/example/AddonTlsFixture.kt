package com.example

import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Generate one disposable localhost certificate per test process. Production uses system trust. */
internal object AddonTlsFixture {
    private data class Contexts(val server: SSLContext, val client: SSLContext, val trust: X509TrustManager)

    private val contexts by lazy {
        val directory = kotlin.io.path.createTempDirectory("wavtape-addon-tls").toFile()
        try {
            val file = File(directory, "localhost.p12")
            val password = "local-fixture"
            val executable = File(System.getProperty("java.home"), "bin/keytool")
            val process = ProcessBuilder(executable.absolutePath, "-genkeypair", "-alias", "localhost",
                "-keyalg", "EC", "-storetype", "PKCS12", "-keystore", file.absolutePath,
                "-storepass", password, "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost",
                "-validity", "7").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            check(process.waitFor() == 0) { output }
            val keys = KeyStore.getInstance("PKCS12").apply {
                file.inputStream().use { load(it, password.toCharArray()) }
            }
            val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keys, password.toCharArray())
            }
            val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("localhost", keys.getCertificate("localhost"))
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(trusted)
            }.trustManagers.single() as X509TrustManager
            Contexts(SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) },
                SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }, trust)
        } finally {
            directory.deleteRecursively()
        }
    }

    val server: SSLContext get() = contexts.server
    fun client() = okhttp3.OkHttpClient.Builder().sslSocketFactory(contexts.client.socketFactory, contexts.trust)
}

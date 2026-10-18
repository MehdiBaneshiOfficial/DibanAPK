package ir.diban.android

import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Checks whether Diban's MITM CA is installed in the user's credential store. */
object CaTrust {
    private fun load(path: String): X509Certificate? = try {
        FileInputStream(File(path)).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
    } catch (_: Exception) {
        null
    }

    /** True when exactly our certificate is present among the user-installed CAs. */
    fun isInstalled(caPath: String): Boolean {
        val ours = load(caPath)?.encoded ?: return false
        return try {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            ks.aliases().asSequence().any { alias ->
                alias.startsWith("user:") &&
                    (ks.getCertificate(alias) as? X509Certificate)?.encoded?.contentEquals(ours) == true
            }
        } catch (_: Exception) {
            false
        }
    }
}

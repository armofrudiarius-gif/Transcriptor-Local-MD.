package md.localtranscript

import android.content.Context
import android.net.Uri
import java.security.MessageDigest

object AudioEvidence {
    fun sha256(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1024 * 1024)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Fișierul audio nu poate fi deschis" }
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

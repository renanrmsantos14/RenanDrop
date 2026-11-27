package dev.renandrop.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.renandrop.protocol.FileStore
import dev.renandrop.protocol.PairedPeer
import dev.renandrop.protocol.Store
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Identidade cifrada com AES-256-GCM; a chave fica no Android Keystore (não sai do aparelho nem do app).
 * Os pareamentos (chaves públicas e nomes) ficam em `peers.json` no armazenamento privado do app.
 * Nenhum segredo vai para log; se a chave do Keystore sumir com o arquivo ainda presente, falha em vez de
 * trocar a identidade (trocar perderia todos os pareamentos).
 */
class KeystoreStore(private val dir: File) : Store {
    private val peersStore = FileStore(dir)
    private val identityFile = File(dir, "identity.enc")

    @Synchronized
    override fun loadIdentity(): ByteArray? {
        if (!identityFile.exists()) return null
        val blob = identityFile.readBytes()
        if (blob.size <= IV_LEN + 16) throw IOException("identidade ilegível")
        val key = existingKey() ?: throw IOException("chave da identidade não está mais no Keystore")
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, IV_LEN))
            c.updateAAD(AAD)
            c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
        } catch (e: Exception) {
            throw IOException("não foi possível decifrar a identidade", e)
        }
    }

    @Synchronized
    override fun saveIdentity(secret: ByteArray) {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
        c.updateAAD(AAD)
        val enc = c.doFinal(secret)
        val tmp = File(dir, "identity.enc.tmp")
        tmp.writeBytes(c.iv + enc)
        Files.move(tmp.toPath(), identityFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    override fun loadPeers(): List<PairedPeer> = peersStore.loadPeers()

    override fun savePeers(peers: List<PairedPeer>) = peersStore.savePeers(peers)

    private fun existingKey(): SecretKey? {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return ks.getKey(ALIAS, null) as? SecretKey
    }

    private fun createKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "renandrop.identity.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        val AAD = "RenanDrop identity".toByteArray()
    }
}

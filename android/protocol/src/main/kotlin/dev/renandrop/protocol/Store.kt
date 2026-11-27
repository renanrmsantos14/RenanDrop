package dev.renandrop.protocol

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Aparelho pareado. `publicKey` é o ponto P-256 em SEC1 (65 bytes); `id` é o hex de 32 caracteres. */
class PairedPeer(
    val id: String,
    val name: String,
    val publicKey: ByteArray,
    val pairedAt: Long,
    /** `null` segue a configuração global do nó. */
    val autoAccept: Boolean? = null,
)

/**
 * Persistência que o chamador fornece. O módulo nunca grava nada por conta própria: no Android,
 * guarde o segredo da identidade cifrado (Keystore) e a lista de aparelhos onde preferir.
 * As chamadas podem vir de qualquer thread.
 */
interface Store {
    /** Segredo da identidade (32 bytes), ou `null` se ainda não existe. Falhe com exceção se não conseguir ler. */
    fun loadIdentity(): ByteArray?

    fun saveIdentity(secret: ByteArray)

    fun loadPeers(): List<PairedPeer>

    fun savePeers(peers: List<PairedPeer>)
}

/** Guarda tudo na memória (testes). */
class MemoryStore(private var secret: ByteArray? = null) : Store {
    private var peers: List<PairedPeer> = emptyList()

    @Synchronized
    override fun loadIdentity(): ByteArray? = secret?.copyOf()

    @Synchronized
    override fun saveIdentity(secret: ByteArray) {
        this.secret = secret.copyOf()
    }

    @Synchronized
    override fun loadPeers(): List<PairedPeer> = peers

    @Synchronized
    override fun savePeers(peers: List<PairedPeer>) {
        this.peers = peers.toList()
    }
}

/**
 * Pasta de perfil no mesmo formato do núcleo Rust: `identity.bin` (32 bytes) e `peers.json`.
 * Serve para desktop e testes; no Android prefira um [Store] próprio com a chave protegida.
 */
class FileStore(private val dir: File) : Store {
    init {
        dir.mkdirs()
    }

    private val identityFile get() = File(dir, "identity.bin")
    private val peersFile get() = File(dir, "peers.json")

    @Synchronized
    override fun loadIdentity(): ByteArray? = if (identityFile.exists()) identityFile.readBytes() else null

    @Synchronized
    override fun saveIdentity(secret: ByteArray) {
        writeAtomic(identityFile, secret)
        try {
            Files.setPosixFilePermissions(identityFile.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    override fun loadPeers(): List<PairedPeer> {
        if (!peersFile.exists()) return emptyList()
        return try {
            Json.parse(peersFile.readBytes()).let { it as List<*> }.map {
                val o = it.obj()
                PairedPeer(
                    id = o.str("id"),
                    name = o.str("name"),
                    publicKey = o.str("pubkey").unhex(),
                    pairedAt = o.longOr("paired_at", 0),
                    autoAccept = o["auto_accept"] as? Boolean,
                )
            }
        } catch (e: Exception) {
            throw IOException("peers.json ilegível: ${e.message}", e)
        }
    }

    @Synchronized
    override fun savePeers(peers: List<PairedPeer>) {
        val arr = peers.map {
            linkedMapOf(
                "id" to it.id, "name" to it.name, "pubkey" to it.publicKey.hex(),
                "paired_at" to it.pairedAt, "auto_accept" to it.autoAccept,
            )
        }
        writeAtomic(peersFile, Json.write(arr))
    }

    private fun writeAtomic(f: File, data: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(data)
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

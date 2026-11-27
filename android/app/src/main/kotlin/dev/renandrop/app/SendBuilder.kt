package dev.renandrop.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import dev.renandrop.protocol.ByteSource
import dev.renandrop.protocol.RandomReader
import dev.renandrop.protocol.SendItem
import dev.renandrop.protocol.fileSource
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID

/** Nome, tamanho e data reais de um item vindo do seletor de arquivos ou de "Compartilhar". */
class Described(val uri: Uri, val name: String, val size: Long, val mtime: Long)

/**
 * Transforma URIs de conteúdo (arquivos, pastas, texto compartilhado) em itens de envio.
 * Os bytes são lidos por streaming do `ContentResolver`, uma abertura independente por pista;
 * nada é copiado, exceto fontes sem tamanho conhecido ou sem acesso posicional (cache temporário).
 */
object SendBuilder {
    private const val MAX_ITEMS = 200_000

    fun describe(cr: ContentResolver, uri: Uri): Described {
        if (uri.scheme == "file") {
            val f = File(uri.path.orEmpty())
            return Described(uri, f.name.ifEmpty { "arquivo" }, if (f.isFile) f.length() else -1, f.lastModified())
        }
        var name: String? = null
        var size = -1L
        var mtime = 0L
        try {
            cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                    val lm = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val dm = c.getColumnIndex("date_modified")
                    if (lm >= 0 && !c.isNull(lm)) mtime = c.getLong(lm)
                    else if (dm >= 0 && !c.isNull(dm)) mtime = c.getLong(dm) * 1000
                }
            }
        } catch (_: Exception) {
        }
        if (size < 0) {
            size = try {
                cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
            } catch (_: Exception) {
                -1
            }
        }
        return Described(uri, cleanName(name ?: uri.lastPathSegment ?: "arquivo"), size, mtime)
    }

    /** Itens de uma lista de URIs; `text` (texto compartilhado) vira um `.txt`. */
    fun items(ctx: Context, uris: List<Uri>, text: String? = null): List<SendItem> {
        val cr = ctx.contentResolver
        val out = ArrayList<SendItem>()
        for (u in uris) {
            val d = describe(cr, u)
            out.add(SendItem(d.name, maxOf(d.size, 0), d.mtime, false, sourceOf(ctx, d)))
            if (out.size > MAX_ITEMS) throw IOException("itens demais")
        }
        if (!text.isNullOrEmpty()) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.add(SendItem("Texto compartilhado.txt", bytes.size.toLong(), System.currentTimeMillis(), false, memorySource(bytes)))
        }
        if (out.isEmpty()) throw IOException("nada para enviar")
        // Nomes repetidos na mesma seleção: o receptor renomeia sozinho ("nome (2).ext").
        return out
    }

    /** Pasta escolhida com `ACTION_OPEN_DOCUMENT_TREE`: caminho relativo com a pasta de topo; pastas vazias seguem como item de pasta. */
    fun treeItems(ctx: Context, tree: Uri): List<SendItem> {
        val cr = ctx.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val rootName = cleanName(describe(cr, rootUri).name.ifEmpty { "pasta" })
        val out = ArrayList<SendItem>()
        walk(ctx, tree, rootId, rootName, out)
        out.sortBy { it.path }
        return out
    }

    fun treeName(cr: ContentResolver, tree: Uri): String =
        cleanName(describe(cr, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))).name)

    private fun walk(ctx: Context, tree: Uri, parentId: String, rel: String, out: MutableList<SendItem>) {
        val cr = ctx.contentResolver
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        var any = false
        val dirs = ArrayList<Pair<String, String>>()
        cr.query(kids, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                any = true
                val id = c.getString(0)
                val name = cleanName(c.getString(1) ?: "item")
                val isDir = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                if (isDir) {
                    dirs.add(id to "$rel/$name")
                } else {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    val d = Described(uri, name, if (c.isNull(3)) -1 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
                    out.add(SendItem("$rel/$name", maxOf(d.size, 0), d.mtime, false, sourceOf(ctx, d)))
                    if (out.size > MAX_ITEMS) throw IOException("itens demais na pasta")
                }
            }
        }
        if (!any) out.add(SendItem(rel, 0, 0, true, null))
        for ((id, path) in dirs) walk(ctx, tree, id, path, out)
    }

    private fun cleanName(n: String): String = n.replace('/', '_').replace('\\', '_').trim().ifEmpty { "arquivo" }

    private fun sourceOf(ctx: Context, d: Described): ByteSource {
        if (d.size >= 0) return UriSource(ctx.contentResolver, d.uri)
        // Tamanho desconhecido (fluxo, arquivo virtual): copia uma vez para o cache, pois o envio precisa do tamanho e de leitura posicional.
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, UUID.randomUUID().toString())
        ctx.contentResolver.openInputStream(d.uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            ?: throw IOException("não foi possível abrir ${d.name}")
        return fileSource(f)
    }

    private fun memorySource(bytes: ByteArray) = ByteSource {
        object : RandomReader {
            override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int) {
                if (pos + len > bytes.size) throw EOFException("texto menor que o esperado")
                System.arraycopy(bytes, pos.toInt(), buf, off, len)
            }

            override fun close() {}
        }
    }
}

/** Leitura posicional de um URI de conteúdo: cada pista abre o seu descritor. */
class UriSource(private val cr: ContentResolver, private val uri: Uri) : ByteSource {
    override fun open(): RandomReader {
        val pfd = cr.openFileDescriptor(uri, "r") ?: throw IOException("não foi possível abrir o arquivo")
        val ch = FileInputStream(pfd.fileDescriptor).channel
        return object : RandomReader {
            override fun readFully(pos: Long, buf: ByteArray, off: Int, len: Int) {
                var p = pos
                var o = off
                var left = len
                while (left > 0) {
                    val n = ch.read(ByteBuffer.wrap(buf, o, left), p)
                    if (n < 0) throw EOFException("arquivo menor que o esperado")
                    p += n
                    o += n
                    left -= n
                }
            }

            override fun close() {
                try {
                    ch.close()
                } finally {
                    pfd.close()
                }
            }
        }
    }
}

/** O que a pessoa escolheu enviar: arquivos, uma pasta ou texto compartilhado. */
class Staged(val uris: List<Uri>, val tree: Uri? = null, val text: String? = null) {
    val isEmpty: Boolean get() = uris.isEmpty() && tree == null && text.isNullOrEmpty()

    /** Itens de envio (consulta o provedor de conteúdo: chame fora da thread principal). */
    fun build(ctx: Context): List<SendItem> =
        if (tree != null) SendBuilder.treeItems(ctx, tree) else SendBuilder.items(ctx, uris, text)

    /** Texto curto para a pessoa conferir antes de enviar ("foto.jpg e mais 2 · 14,2 MB"). Fora da thread principal. */
    fun describe(ctx: Context): String {
        val cr = ctx.contentResolver
        if (tree != null) return "Pasta ${SendBuilder.treeName(cr, tree)}"
        val ds = uris.take(50).map { SendBuilder.describe(cr, it) }
        val names = ds.map { it.name }.toMutableList()
        if (!text.isNullOrEmpty()) names.add("Texto compartilhado")
        if (names.isEmpty()) return ""
        val total = ds.sumOf { maxOf(it.size, 0) }
        val more = uris.size + (if (text.isNullOrEmpty()) 0 else 1) - 1
        val head = if (more > 0) "${names.first()} e mais $more" else names.first()
        return if (total > 0) "$head · ${Format.bytes(total)}" else head
    }
}

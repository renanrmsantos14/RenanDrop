package dev.renandrop.app

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Leva o que o nó gravou na pasta interna do app para o destino final, sem carregar nada na memória
 * (cópia em blocos de 256 KiB) e apagando cada arquivo da origem depois de copiado.
 *  - Downloads/RenanDrop pelo MediaStore (padrão; não precisa de permissão no Android 10+);
 *  - ou uma pasta escolhida pelo seletor de arquivos (SAF).
 * Pastas vazias só são recriadas no SAF (o MediaStore não tem pasta sem arquivo).
 */
class Exporter(ctx: Context) {
    private val cr: ContentResolver = ctx.contentResolver

    fun export(tops: List<File>, target: RecvTarget): Saved {
        require(tops.isNotEmpty()) { "nada recebido" }
        return when (target) {
            RecvTarget.Downloads -> toDownloads(tops)
            is RecvTarget.Tree -> toTree(tops, Uri.parse(target.uri))
        }
    }

    // ───────────── Downloads/RenanDrop (MediaStore) ─────────────

    private fun toDownloads(tops: List<File>): Saved {
        var single: Uri? = null
        var singleMime: String? = null
        for (top in tops) {
            if (top.isDirectory) {
                top.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                    val rel = top.name + "/" + f.relativeTo(top).path.replace(File.separatorChar, '/')
                    val (dir, name) = splitRelPath(rel)
                    insertDownload(f, dir, name)
                }
                top.deleteRecursively()
            } else {
                val uri = insertDownload(top, "", top.name)
                if (tops.size == 1) {
                    single = uri
                    singleMime = mimeOf(top.name)
                }
            }
        }
        val onlyDir = tops.singleOrNull()?.takeIf { it.isDirectory }?.name
        return Saved(single?.toString(), singleMime, folderDocUri(downloadsDocumentId(onlyDir)), "Downloads/RenanDrop")
    }

    private fun insertDownload(src: File, relDir: String, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(name))
            put(MediaStore.Downloads.RELATIVE_PATH, mediaRelativePath(relDir))
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("o Android recusou criar $name em Downloads")
        try {
            val out = cr.openOutputStream(uri, "w") ?: throw IOException("não foi possível gravar $name")
            out.use { o -> FileInputStream(src).use { it.copyTo(o, BUF) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try {
                cr.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw if (e is IOException) e else IOException(e.message, e)
        }
        src.delete()
        return uri
    }

    // ───────────── Pasta escolhida (SAF) ─────────────

    private fun toTree(tops: List<File>, tree: Uri): Saved {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var single: Uri? = null
        var singleMime: String? = null
        var folder = root
        for (top in tops) {
            if (top.isDirectory) {
                val dirUri = createDoc(root, DocumentsContract.Document.MIME_TYPE_DIR, top.name)
                if (tops.size == 1) folder = dirUri
                copyTree(top, dirUri)
                top.deleteRecursively()
            } else {
                val uri = copyFile(top, root)
                if (tops.size == 1) {
                    single = uri
                    singleMime = mimeOf(top.name)
                }
            }
        }
        return Saved(single?.toString(), singleMime, folder.toString(), "pasta escolhida")
    }

    private fun copyTree(dir: File, dirUri: Uri) {
        for (f in dir.listFiles()?.sortedBy { it.name }.orEmpty()) {
            if (f.isDirectory) copyTree(f, createDoc(dirUri, DocumentsContract.Document.MIME_TYPE_DIR, f.name))
            else copyFile(f, dirUri)
        }
    }

    private fun copyFile(src: File, parent: Uri): Uri {
        val uri = createDoc(parent, mimeOf(src.name), src.name)
        try {
            val out = cr.openOutputStream(uri, "w") ?: throw IOException("não foi possível gravar ${src.name}")
            out.use { o -> FileInputStream(src).use { it.copyTo(o, BUF) } }
        } catch (e: Exception) {
            try {
                DocumentsContract.deleteDocument(cr, uri)
            } catch (_: Exception) {
            }
            throw if (e is IOException) e else IOException(e.message, e)
        }
        src.delete()
        return uri
    }

    private fun createDoc(parent: Uri, mime: String, name: String): Uri =
        DocumentsContract.createDocument(cr, parent, mime, name) ?: throw IOException("a pasta escolhida recusou criar $name")

    private companion object {
        const val BUF = 256 * 1024

        fun mimeOf(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return (if (ext.isEmpty()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)) ?: "application/octet-stream"
        }

        fun folderDocUri(docId: String): String =
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId).toString()
    }
}

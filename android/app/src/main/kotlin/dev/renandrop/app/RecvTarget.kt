package dev.renandrop.app

const val DOWNLOADS_REL = "Download/RenanDrop"

/** Onde os arquivos recebidos ficam no fim: Downloads/RenanDrop (padrão) ou uma pasta escolhida pelo seletor de arquivos. */
sealed interface RecvTarget {
    data object Downloads : RecvTarget
    data class Tree(val uri: String) : RecvTarget
}

/** Pasta escolhida só vale se a permissão ainda existe; senão volta ao padrão. */
fun recvTarget(treeUri: String?, treeGranted: Boolean): RecvTarget =
    if (!treeUri.isNullOrEmpty() && treeGranted) RecvTarget.Tree(treeUri) else RecvTarget.Downloads

fun folderLabel(t: RecvTarget, treeName: String?): String = when (t) {
    RecvTarget.Downloads -> "Downloads/RenanDrop"
    is RecvTarget.Tree -> treeName?.takeIf { it.isNotBlank() } ?: "Pasta escolhida"
}

/** `Fotos/2024/a.jpg` vira (`Fotos/2024`, `a.jpg`). */
fun splitRelPath(rel: String): Pair<String, String> {
    val clean = rel.trim('/')
    val i = clean.lastIndexOf('/')
    return if (i < 0) "" to clean else clean.substring(0, i) to clean.substring(i + 1)
}

/** `RELATIVE_PATH` do MediaStore para uma subpasta do destino padrão (termina com `/`). */
fun mediaRelativePath(dir: String): String {
    val d = dir.trim('/')
    return if (d.isEmpty()) "$DOWNLOADS_REL/" else "$DOWNLOADS_REL/$d/"
}

/** Id de documento do provedor de armazenamento externo para uma subpasta do destino padrão. */
fun downloadsDocumentId(subDir: String?): String {
    val d = subDir?.trim('/').orEmpty()
    return if (d.isEmpty()) "primary:$DOWNLOADS_REL" else "primary:$DOWNLOADS_REL/$d"
}

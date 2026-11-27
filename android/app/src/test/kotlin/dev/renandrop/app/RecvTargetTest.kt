package dev.renandrop.app

import kotlin.test.Test
import kotlin.test.assertEquals

class RecvTargetTest {
    @Test
    fun pastaEscolhidaSoValeComPermissao() {
        assertEquals(RecvTarget.Downloads, recvTarget(null, true))
        assertEquals(RecvTarget.Downloads, recvTarget("", true))
        assertEquals(RecvTarget.Downloads, recvTarget("content://x/tree/y", false))
        assertEquals(RecvTarget.Tree("content://x/tree/y"), recvTarget("content://x/tree/y", true))
    }

    @Test
    fun rotuloDaPasta() {
        assertEquals("Downloads/RenanDrop", folderLabel(RecvTarget.Downloads, "qualquer"))
        assertEquals("Fotos", folderLabel(RecvTarget.Tree("u"), "Fotos"))
        assertEquals("Pasta escolhida", folderLabel(RecvTarget.Tree("u"), " "))
        assertEquals("Pasta escolhida", folderLabel(RecvTarget.Tree("u"), null))
    }

    @Test
    fun caminhosRelativos() {
        assertEquals("Fotos/2024" to "a.jpg", splitRelPath("Fotos/2024/a.jpg"))
        assertEquals("" to "a.jpg", splitRelPath("/a.jpg"))
        assertEquals("Download/RenanDrop/", mediaRelativePath(""))
        assertEquals("Download/RenanDrop/Fotos/2024/", mediaRelativePath("/Fotos/2024/"))
        assertEquals("primary:Download/RenanDrop", downloadsDocumentId(null))
        assertEquals("primary:Download/RenanDrop/Fotos", downloadsDocumentId("Fotos/"))
    }
}

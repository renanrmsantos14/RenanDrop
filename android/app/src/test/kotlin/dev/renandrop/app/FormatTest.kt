package dev.renandrop.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun bytesUsaVirgulaEUnidadeCerta() {
        assertEquals("0 B", Format.bytes(0))
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1,5 KB", Format.bytes(1536))
        assertEquals("14,2 MB", Format.bytes((14.2 * 1024 * 1024).toLong()))
        assertEquals("120 MB", Format.bytes(120L * 1024 * 1024))
        assertEquals("2,0 GB", Format.bytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun velocidadeEArquivos() {
        assertEquals("1,0 MB/s", Format.speed(1024.0 * 1024))
        assertEquals("1 arquivo", Format.files(1))
        assertEquals("3 arquivos", Format.files(3))
    }

    @Test
    fun percentualFicaEntreZeroECem() {
        assertEquals(0, Format.percent(10, 0))
        assertEquals(50, Format.percent(50, 100))
        assertEquals(100, Format.percent(500, 100))
        assertEquals(0, Format.percent(-5, 100))
    }

    @Test
    fun codigoEmDoisBlocos() {
        assertEquals("123 456", Format.code("123456"))
        assertEquals("12", Format.code("12"))
    }

    @Test
    fun versaoComDataEmSaoPaulo() {
        assertEquals("v0.0.7 · 27/11/2026 14:05", Format.buildLabel("0.0.7", "2026-11-27T14:05:09-03:00"))
        assertEquals("v0.0.7 · 27/11/2026 14:05", Format.buildLabel("0.0.7", "2026-11-27T17:05:09Z"))
        assertEquals("v0.0.7 · 27/11/2026 14:05", Format.buildLabel("0.0.7", "2026-11-27T14:05:09"))
        assertEquals("v0.0.7", Format.buildLabel("0.0.7", ""))
        assertEquals("v0.0.7", Format.buildLabel("0.0.7", "lixo"))
    }

    @Test
    fun inicialDoNome() {
        assertEquals("R", Format.initial("  renan"))
        assertEquals("?", Format.initial(" "))
    }

    @Test
    fun temaAutomaticoSegueOSistema() {
        assertEquals(true, isDarkTheme(ThemeMode.AUTO, true))
        assertEquals(false, isDarkTheme(ThemeMode.AUTO, false))
        assertEquals(false, isDarkTheme(ThemeMode.LIGHT, true))
        assertEquals(true, isDarkTheme(ThemeMode.DARK, false))
    }
}

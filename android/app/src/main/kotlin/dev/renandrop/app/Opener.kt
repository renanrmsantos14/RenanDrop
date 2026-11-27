package dev.renandrop.app

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

/** Intents de "Abrir" e "Mostrar na pasta" a partir do que foi salvo. */
object Opener {
    fun open(saved: Saved?): Intent? {
        val uri = saved?.openUri ?: return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), saved.openMime ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Pasta no seletor de arquivos do sistema. */
    fun folder(saved: Saved?): Intent? {
        val uri = saved?.folderUri ?: return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Lista de Downloads, usada quando nenhum app abre a pasta. */
    fun downloads(): Intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Tenta cada intent em ordem; devolve `false` se nenhum app respondeu. */
    fun launch(ctx: Context, vararg intents: Intent?): Boolean {
        for (i in intents) {
            if (i == null) continue
            try {
                ctx.startActivity(i)
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }
}

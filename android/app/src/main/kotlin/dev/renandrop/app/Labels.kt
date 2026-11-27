package dev.renandrop.app

import dev.renandrop.protocol.Direction
import dev.renandrop.protocol.Route
import dev.renandrop.protocol.TransferInfo
import dev.renandrop.protocol.TransferState

/** Textos da interface (mesmos do app do PC). */
fun stateLabel(s: TransferState): String = when (s) {
    TransferState.QUEUED -> "Na fila"
    TransferState.CONNECTING -> "Conectando"
    TransferState.WAITING -> "Aguardando o aceite"
    TransferState.SENDING -> "Enviando"
    TransferState.RECEIVING -> "Recebendo"
    TransferState.RETRYING -> "Tentando de novo"
    TransferState.DONE -> "Concluído"
    TransferState.FAILED -> "Falhou"
    TransferState.CANCELLED -> "Cancelado"
    TransferState.DECLINED -> "Recusado"
}

fun routeLabel(r: Route?): String = when (r) {
    Route.LAN -> "Rede local"
    Route.TAILNET -> "Tailscale"
    Route.LOCAL -> "Neste aparelho"
    Route.INTERNET -> "Internet"
    null -> ""
}

enum class TailscaleStatus { OK, MISSING, DISCONNECTED, OFF }

/** `enabled`: opção ligada nas configurações; `hasTailnetAddress`: alguma interface tem IP da tailnet; `installed`: app instalado. */
fun tailscaleStatus(enabled: Boolean, hasTailnetAddress: Boolean, installed: Boolean): TailscaleStatus = when {
    !enabled -> TailscaleStatus.OFF
    hasTailnetAddress -> TailscaleStatus.OK
    installed -> TailscaleStatus.DISCONNECTED
    else -> TailscaleStatus.MISSING
}

fun tailscaleText(s: TailscaleStatus): String = when (s) {
    TailscaleStatus.OK -> "Tailscale conectado: aparelhos fora de casa aparecem aqui quando adicionados."
    TailscaleStatus.MISSING -> "Tailscale não encontrado: o app funciona só na rede local."
    TailscaleStatus.DISCONNECTED -> "Tailscale desconectado: só a rede local por enquanto."
    TailscaleStatus.OFF -> "Tailscale desligado nas configurações."
}

/** Percentual de uma transferência para a barra de progresso. */
fun percentOf(t: TransferInfo): Int =
    if (t.total > 0) Format.percent(t.bytes, t.total) else if (t.state == TransferState.DONE) 100 else 0

/** Linha de detalhes: estado, bytes, velocidade (só enquanto ativa). A rota é mostrada à parte. */
fun metaParts(t: TransferInfo): List<String> = buildList {
    add(stateLabel(t.state))
    if (t.total > 0) add("${Format.bytes(t.bytes)} de ${Format.bytes(t.total)}")
    if (!t.finished && t.speed > 0) add(Format.speed(t.speed))
}

fun directionLine(t: TransferInfo): String =
    "${if (t.direction == Direction.SEND) "Para" else "De"} ${t.peerName} · ${Format.files(t.files)}"

/** Resumo da fila para a notificação fixa. `null` quando não há nada ativo. */
data class QueueSummary(val title: String, val text: String, val percent: Int)

fun queueSummary(all: List<TransferInfo>): QueueSummary? {
    val active = all.filter { !it.finished }
    if (active.isEmpty()) return null
    val live = active.filter { it.state == TransferState.SENDING || it.state == TransferState.RECEIVING }
    val bytes = live.sumOf { it.bytes }
    val total = live.sumOf { it.total }
    val speed = live.sumOf { it.speed }
    val percent = if (total > 0) Format.percent(bytes, total) else 0
    val first = active.first()
    val verb = when (first.state) {
        TransferState.RECEIVING -> "Recebendo"
        TransferState.SENDING -> "Enviando"
        else -> stateLabel(first.state)
    }
    val title = if (active.size == 1) "$verb ${first.summary}" else "${active.size} transferências em andamento"
    val parts = buildList {
        if (active.size == 1) add(if (first.direction == Direction.SEND) "Para ${first.peerName}" else "De ${first.peerName}")
        if (total > 0) add("$percent%")
        if (speed > 0) add(Format.speed(speed))
    }
    return QueueSummary(title, parts.joinToString(" · "), percent)
}

/** Termina e ainda não foi para o histórico? Recebimento concluído precisa copiar para o destino antes. */
fun needsExport(t: TransferInfo): Boolean = t.direction == Direction.RECV && t.state == TransferState.DONE && t.paths.isNotEmpty()

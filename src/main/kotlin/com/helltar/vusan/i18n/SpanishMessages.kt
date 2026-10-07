package com.helltar.vusan.i18n

import com.helltar.vusan.agent.ToolActivity
import com.helltar.vusan.common.escapeHtml
import kotlin.time.Duration

internal object SpanishMessages : Messages {

    override val startReply = "Hola. Solo dime qué necesitas"

    override val busyReply = "Espera, todavía estoy con tu petición anterior"

    override val fallbackErrorReply = "Algo falló, inténtalo otra vez"

    override val overloadedReply = "Ahora mismo hay demasiadas peticiones, dame un momento e inténtalo de nuevo"
    override val signInRequiredReply = "Mi sesión en el servicio de IA caducó, hay que iniciar sesión de nuevo"

    override val contentPolicyReply = "Eso no pasó, plantéalo de otra forma"

    override val formattingAsFileNotice =
        "Telegram no pudo mostrar el formato, así que aquí va la respuesta completa como archivo"

    override val privateBlockedNotice =
        "Quería escribirte en privado pero no puedo. Abre mi chat, pulsa /start y vuelve a preguntar"

    override val conversationClearedReply =
        "El historial de este chat está borrado. Los demás chats, la memoria y las tareas programadas siguen igual"

    override val turnStoppedNotice = "Paro aquí. Lo que ya envié se queda, el historial y los archivos no cambian"

    override val nothingToStopReply = "Ahora mismo no hago nada, no hay nada que detener"

    override val turnStopButton = "⏹ Detener"

    override val turnStopNotOwnerAlert = "Esta petición es de otra persona."

    override val voiceEmptyReply = "No se oye nada en ese mensaje de voz, inténtalo otra vez o escríbelo"

    override val voiceTranscriptionFailedReply = "No pude entender ese mensaje de voz, mejor escríbelo"

    override val inlineChoiceNotOwnerAlert = "Esta elección era para otra persona."

    override val inlineChoiceUnavailableAlert = "Esta elección ya no está disponible."

    override val inlineChoiceErrorAlert = "No pude aplicar esa elección — inténtalo otra vez."

    override val taskMenuNotOwnerAlert = "Este menú de tareas es de otra persona."

    override val taskMenuUnavailableAlert = "Esa tarea ya no está disponible."

    override val taskMenuPastOnceAlert = "Esta tarea de una sola vez ya pasó, así que no se puede reanudar."

    override val taskMenuErrorAlert = "No pude actualizar la tarea — inténtalo otra vez."

    override val taskMenuRefreshButton = "🔄 Actualizar"

    override val taskMenuBackButton = "↩️ Volver"

    override val taskMenuDeleteButton = "🗑 Eliminar"

    override val tasksCommandDescription = "Gestionar tareas programadas"
    override val clearCommandDescription = "Borrar el historial de la conversación"
    override val stopCommandDescription = "Detener lo que estoy haciendo ahora"

    override val ephemeralChatReply =
        "Esto solo lo vemos tú y yo, y yo respondo a la vista de todos. Escríbeme un mensaje normal"

    override fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String =
        "Ese mensaje de voz dura ${durationSeconds}s y yo escucho hasta ${maxSeconds}s. Manda uno más corto o escríbelo"

    override fun subscriptionLimitReply(untilReset: Duration?): String =
        untilReset
            ?.let { "Llegué al límite de uso, se renueva en unos ${waitLabel(it)}, inténtalo entonces" }
            ?: "Llegué al límite de uso por ahora, inténtalo más tarde"

    private fun waitLabel(untilReset: Duration): String =
        untilReset.toComponents { days, hours, minutes, _, _ ->
            when {
                days > 0 -> "$days d $hours h"
                hours > 0 -> "$hours h $minutes min"
                minutes > 0 -> "$minutes min"
                else -> "un minuto"
            }
        }

    override fun inlineChoiceSelected(option: String) = "✅ Elegido: $option"

    override fun taskMenuTitle(currentChatOnly: Boolean): String =
        if (currentChatOnly)
            "<b>🗓 Tus tareas programadas en este chat</b>"
        else
            "<b>🗓 Tus tareas programadas</b>"

    override fun taskMenuCapacity(currentChatOnly: Boolean, listed: Int, total: Int, limit: Int): String =
        if (currentChatOnly)
            "<i>En este chat: $listed\nEn todos los chats: $total · límite: $limit</i>"
        else
            "<i>Tareas: $total · límite: $limit</i>"

    override fun taskMenuEmpty(currentChatOnly: Boolean): String =
        if (currentChatOnly)
            "No tienes tareas programadas en este chat."
        else
            "No tienes tareas programadas."

    override fun taskMenuHiddenNotice(hidden: Int) =
        "<i>Otras $hidden no caben aquí — pregúntame por ellas con tus palabras.</i>"

    override fun taskMenuItem(
        id: Long,
        label: String,
        nextFire: String,
        recurrence: String,
        paused: Boolean,
    ): String =
        buildString {
            append("<b>#$id · $label</b>\n")
            append("🕒 $nextFire\n")
            append("🔁 $recurrence\n")
            append(if (paused) "⏸ En pausa" else "🟢 Activa")
        }

    override fun taskMenuPauseButton(id: Long) = "⏸ Pausar #$id"

    override fun taskMenuResumeButton(id: Long) = "▶️ Reanudar #$id"

    override fun taskMenuCancelButton(id: Long) = "🗑 Cancelar #$id"

    override fun taskMenuDeleteConfirmation(id: Long, label: String): String =
        "<b>¿Eliminar la tarea #$id · $label?</b>\n\nEsto no se puede deshacer."

    override fun taskMissedNotice(id: Long, title: String?, scheduledFor: String): String {
        val label = title?.let { " «$it»" } ?: ""

        return "⏰ Me salté la tarea #$id$label programada para $scheduledFor: estaba sin conexión."
    }

    override fun taskFailedNotice(id: Long, title: String?): String {
        val label = title?.let { " «$it»" } ?: ""

        return "⚠️ La tarea #$id$label no llegó a nada: no pude terminarla ni tras varios intentos."
    }

    override fun taskScheduledByNotice(mention: String) = "⏰ Programado por $mention"

    override fun taskFollowUpNotice(mention: String) = "💬 Retomando la conversación con $mention"

    override fun fallbackModelNote(model: String, primaryBackIn: Duration?): String =
        "↳ en el modelo de reserva: <code>${model.escapeHtml()}</code>" +
                primaryBackIn?.let { "\n↳ el habitual vuelve en unos <b>${waitLabel(it)}</b>" }.orEmpty()

    override fun progressLabel(activity: ToolActivity): String =

        when (activity) {
            ToolActivity.WRITING -> "Escribiendo la respuesta"
            ToolActivity.SEARCHING_WEB -> "Buscando en la web"
            ToolActivity.READING_PAGE -> "Leyendo la página"
            ToolActivity.READING_CHANNEL -> "Leyendo el canal"
            ToolActivity.READING_TRANSCRIPT -> "Leyendo la transcripción del vídeo"
            ToolActivity.READING_CHAT_LOG -> "Leyendo el historial del chat"
            ToolActivity.SEARCHING_IMAGES -> "Buscando imágenes"
            ToolActivity.SEARCHING_GIF -> "Buscando un GIF"
            ToolActivity.DRAWING -> "Dibujando"
            ToolActivity.RUNNING_CODE -> "Ejecutando código"
            ToolActivity.LOOKING_AT_IMAGE -> "Mirando la imagen"
            ToolActivity.WATCHING_VIDEO -> "Viendo el vídeo"
            ToolActivity.DOWNLOADING_VIDEO -> "Descargando el vídeo"
            ToolActivity.DOWNLOADING_AUDIO -> "Sacando el audio"
            ToolActivity.SENDING_FILE -> "Preparando el archivo"
            ToolActivity.SPEAKING -> "Grabando un mensaje de voz"
            ToolActivity.REMEMBERING -> "Actualizando lo que recuerdo"
            ToolActivity.MANAGING_TASKS -> "Actualizando tus tareas programadas"
        }
}

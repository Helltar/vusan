package com.helltar.vusan.i18n

import com.helltar.vusan.agent.ToolActivity
import kotlin.time.Duration

internal object SpanishMessages : Messages {

    override val startReply = "¡Hola! Solo dime qué necesitas 👋"

    override val busyReply = "Espera, todavía estoy con tu petición anterior 😊"

    override val fallbackErrorReply = "Algo salió mal, ¿lo intentamos otra vez? 🥲"

    override val overloadedReply = "Ahora mismo tengo demasiadas peticiones — dame un momento e inténtalo de nuevo 🙏"
    override val signInRequiredReply =
        "Hay que renovar mi conexión con el servicio de IA — requiere iniciar sesión de nuevo 🔑"

    override val formattingAsFileNotice =
        "Telegram no pudo mostrar el formato, así que aquí tienes la respuesta completa como archivo 📄"

    override val privateBlockedNotice =
        "Quería escribirte en privado, pero no puedo — abre mi chat, pulsa /start y vuelve a preguntar 😊"

    override val conversationClearedReply =
        "El historial de nuestra conversación en este chat está borrado. " +
                "Los demás chats, la memoria y las tareas programadas siguen intactos. 🧹"

    override val voiceEmptyReply = "No oigo nada en ese mensaje de voz — inténtalo otra vez o escríbelo 🙉"

    override val voiceTranscriptionFailedReply = "No pude transcribir ese mensaje de voz — mejor escríbelo 😊"

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

    override fun voiceTooLongReply(durationSeconds: Long, maxSeconds: Long): String =
        "Ese mensaje de voz dura ${durationSeconds}s — solo puedo transcribir hasta ${maxSeconds}s, " +
                "manda uno más corto o escríbelo"

    override fun subscriptionLimitReply(untilReset: Duration?): String =
        untilReset
            ?.let { "He agotado mi límite de uso — se renueva en unos ${waitLabel(it)}, inténtalo entonces ⏳" }
            ?: "He agotado mi límite de uso — se renueva en un rato, inténtalo más tarde 🙏"

    override fun tokenBudgetExhaustedReply(untilReset: Duration): String =
        "El presupuesto de tokens de hoy está agotado — vuelve en unos ${waitLabel(untilReset)} ⏳"

    override fun tokenShareExhaustedReply(untilReset: Duration): String =
        "El presupuesto de tokens de hoy se está acabando y tu parte ya está gastada — " +
                "guardo el resto para los demás. Vuelve en unos ${waitLabel(untilReset)} ⏳"

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
        paused: Boolean
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
        return "⏰ Me salté la tarea #$id$label programada para $scheduledFor — estaba sin conexión."
    }

    override fun taskFailedNotice(id: Long, title: String?): String {
        val label = title?.let { " «$it»" } ?: ""
        return "⚠️ La tarea #$id$label no llegó a nada — no pude terminarla ni tras varios intentos."
    }

    override fun taskScheduledByNotice(mention: String) = "⏰ Programado por $mention"

    override fun taskFollowUpNotice(mention: String) = "💬 Retomando la conversación con $mention"

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

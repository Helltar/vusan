package com.helltar.vusan.agent.history

enum class ChatRole { USER, ASSISTANT }

data class ChatTurn(val role: ChatRole, val content: String)

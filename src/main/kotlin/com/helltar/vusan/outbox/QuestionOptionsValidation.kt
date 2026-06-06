package com.helltar.vusan.outbox

private const val MAX_QUESTION_LENGTH = 300
private const val MIN_OPTIONS = 2
private const val MAX_OPTIONS = 10
private const val MAX_OPTION_LENGTH = 100
internal const val MAX_EXPLANATION_LENGTH = 200

internal fun validateQuestionAndOptions(kind: String, question: String, options: List<String>) {
    require(question.isNotEmpty()) {
        "$kind question must not be empty"
    }

    require(question.length <= MAX_QUESTION_LENGTH) {
        "$kind question must be at most $MAX_QUESTION_LENGTH characters"
    }

    require(options.size in MIN_OPTIONS..MAX_OPTIONS) {
        "$kind must have between $MIN_OPTIONS and $MAX_OPTIONS options"
    }

    require(options.none { it.isEmpty() }) {
        "$kind options must not be empty"
    }

    require(options.none { it.length > MAX_OPTION_LENGTH }) {
        "Each ${kind.lowercase()} option must be at most $MAX_OPTION_LENGTH characters"
    }

    require(options.distinctBy { it.lowercase() }.size == options.size) {
        "$kind options must be distinct"
    }
}

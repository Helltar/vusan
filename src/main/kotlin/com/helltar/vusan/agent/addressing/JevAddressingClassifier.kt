package com.helltar.vusan.agent.addressing

import com.helltar.vusan.llm.typesafe.NoulQuestion
import com.helltar.vusan.llm.typesafe.SystemOneClient
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Asks TypeSafe's Jev, which answers with a probability instead of text: the same state and the same wording
 * as [LlmAddressingClassifier], the criteria sent as the question's own.
 *
 * The cut-off is code's, and it moves with the wording and the model version, so change either only against a
 * measurement and pin the version the cut-off was measured on.
 */
class JevAddressingClassifier(
    private val client: SystemOneClient,
    private val model: String,
) : AddressingClassifier {

    override suspend fun isAddressed(input: AddressingInput): Boolean? {
        val question = NoulQuestion(addressingQuestion(input.botNames), ADDRESSED_WHEN, NOT_ADDRESSED_WHEN)
        val probability = client.nouls(model, addressingState(input), mapOf(QUESTION to question))[QUESTION] ?: return null

        // the verdict line that follows says which message; this one says how near the cut-off it was
        log.info { "ambient probability: p=[${"%.2f".format(probability)}] cutOff=[$ADDRESSED_FROM]" }

        return probability >= ADDRESSED_FROM
    }

    private companion object {
        val log = KotlinLogging.logger {}

        const val QUESTION = "addressed"

        // measured on jev-1.14.0: nearly every message for someone else stayed under 0.5, while the name plus a
        // bare command ("vusan tldr") sat at 0.6-0.7. 0.6 takes those in at two false positives on 412 probes;
        // lower lets more in, and answering into someone else's conversation is the expensive mistake
        const val ADDRESSED_FROM = 0.6
    }
}

package com.helltar.vusan.llm.typesafe

import com.helltar.vusan.llm.LlmException
import com.helltar.vusan.llm.postJson
import io.ktor.client.*
import io.ktor.http.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A yes-or-no question about a state, with what counts as each answer. */
data class NoulQuestion(val instructions: String, val whenTrue: String, val whenFalse: String)

/**
 * TypeSafe's System One API, whose models write no text: a request carries a state and named questions, and
 * each comes back as a probability. That is why this is no `LlmClient` — there is no message to send and no
 * reply to read — and why only a role that needs a judgment, never the agent, can run on it.
 */
class SystemOneClient(
    private val http: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = TYPESAFE_API_BASE_URL,
) : AutoCloseable {

    /**
     * The probability of yes for each of [questions] about [state], under the names they were asked by. A
     * question the answer does not hold is left out.
     */
    suspend fun nouls(model: String, state: JsonElement, questions: Map<String, NoulQuestion>): Map<String, Double> {
        val body =
            buildJsonObject {
                put("model", model)
                put("state", state)

                putJsonObject("questions") {
                    questions.forEach { (name, question) ->
                        putJsonObject(name) {
                            put("type", NOUL)
                            put("instructions", question.instructions)

                            putJsonObject("criteria") {
                                put("true", question.whenTrue)
                                put("false", question.whenFalse)
                            }
                        }
                    }
                }
            }

        val reply = http.postJson(PROVIDER, "$baseUrl/v1/systemone", body, mapOf(HttpHeaders.Authorization to "Bearer $apiKey"))
        val answers = reply["answers"] as? JsonObject ?: throw LlmException(PROVIDER, status = null, body = "a reply without answers")

        return questions.keys.mapNotNull { name ->
            (((answers[name] as? JsonObject)?.get(NOUL)) as? JsonPrimitive)?.doubleOrNull?.let { name to it }
        }.toMap()
    }

    override fun close() = http.close()

    private companion object {
        const val PROVIDER = "TypeSafe"
        const val NOUL = "noul"
    }
}

const val TYPESAFE_API_BASE_URL = "https://api.typesafe.ai"

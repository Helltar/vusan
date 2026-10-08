package com.helltar.vusan.tools

import com.helltar.vusan.llm.ToolDefinition
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.valueParameters
import kotlin.reflect.jvm.isAccessible

/**
 * Marks a method of a [ToolSet] as a tool the model may call, with the [description] the model reads
 * about it — the tool's whole interface, besides its arguments. The method has to return a `String`.
 *
 * [readOnly] says the tool changes nothing — no output queued, no file written, no state the next
 * call reads — so the loop may run it alongside the other read-only calls of the same batch instead of
 * one after another. A tool that only looks something up qualifies; a tool whose result lands in the
 * chat or in the sandbox does not, however harmless, because its order against the batch is its meaning.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tool(val description: String, val readOnly: Boolean = false)

/** What the model reads about one argument of a tool. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Arg(val description: String)

/** A class whose [Tool]-annotated methods are offered to the model. */
interface ToolSet

/**
 * One tool as the agent runs it: its definition for the model, and the call that runs the method behind
 * it with the arguments the model sent.
 *
 * Arguments are decoded from the JSON the model wrote by the method's own parameter types: text, whole
 * and decimal numbers, booleans and lists of text, each tolerant of a number spelt as text. A parameter
 * with a default value or a nullable type is optional for the model; a missing required one, or a value
 * of the wrong shape, is an [IllegalArgumentException] the agent answers the call with.
 */
class ToolFunction internal constructor(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val requiredParameters: List<String>,
    /** Whether the loop may run this call alongside the other read-only calls of its batch. */
    val readOnly: Boolean,
    private val invoke: suspend (JsonObject) -> String,
) {

    val definition: ToolDefinition
        get() = ToolDefinition(name, description, parameters)

    suspend fun call(arguments: JsonObject): String = invoke(arguments)

    override fun toString(): String = "ToolFunction($name)"
}

/**
 * The tools this set offers, read off its [Tool] methods by reflection, in a fixed order.
 *
 * Sorted by name rather than left in the order reflection happens to list them, because the tool array
 * is part of the prompt prefix every provider caches and two runs of the same deployment must send it
 * the same way.
 */
fun ToolSet.toolFunctions(): List<ToolFunction> =
    this::class.memberFunctions
        .filter { it.hasAnnotation<Tool>() }
        .sortedBy { it.name }
        .map { toolFunction(this, it) }

private fun toolFunction(instance: ToolSet, function: KFunction<*>): ToolFunction {
    val name = function.name
    val annotation = requireNotNull(function.findAnnotation<Tool>())
    val description = annotation.description

    require(function.returnType.classifier == String::class) { "tool $name must return a String" }

    // a tool set may be a private class of its file; the method is still the model's to call
    function.isAccessible = true

    val parameters = function.valueParameters
    val required = parameters.filter { !it.isOptional && !it.type.isMarkedNullable }.map { it.parameterName }

    parameters.forEach { require(it.schemaType() != null) { "tool $name: parameter ${it.parameterName} has an unsupported type ${it.type}" } }

    val schema =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    parameters.forEach { parameter ->
                        put(
                            parameter.parameterName,
                            buildJsonObject {
                                val type = requireNotNull(parameter.schemaType())

                                // a nullable parameter may be sent as null, and the schema says so
                                if (parameter.type.isMarkedNullable) putJsonArray("type") { add(type); add("null") } else put("type", type)
                                if (parameter.type.classifier == List::class) put("items", buildJsonObject { put("type", "string") })
                                parameter.findAnnotation<Arg>()?.let { put("description", it.description) }
                            },
                        )
                    }
                },
            )
            putJsonArray("required") { required.forEach { add(it) } }
        }

    return ToolFunction(name, description, schema, required, annotation.readOnly) { arguments ->
        val values =
            buildMap {
                put(requireNotNull(function.instanceParameter) { "tool $name is not a method" }, instance)

                parameters.forEach { parameter ->
                    val raw = arguments[parameter.parameterName]

                    when {
                        raw == null || raw is JsonNull ->
                            if (!parameter.isOptional) {
                                require(parameter.type.isMarkedNullable) { "missing required argument `${parameter.parameterName}`" }
                                put(parameter, null)
                            }

                        else -> put(parameter, decodeArgument(parameter, raw))
                    }
                }
            }

        try {
            function.callSuspendBy(values) as String
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }
}

private val KParameter.parameterName: String
    get() = requireNotNull(name) { "a tool parameter has no name" }

private fun KParameter.schemaType(): String? =
    when (type.classifier) {
        String::class -> "string"
        Int::class, Long::class -> "integer"
        Double::class, Float::class -> "number"
        Boolean::class -> "boolean"
        List::class -> if (type.arguments.singleOrNull()?.type?.classifier == String::class) "array" else null
        else -> null
    }

private fun decodeArgument(parameter: KParameter, raw: JsonElement): Any {
    val name = parameter.parameterName
    val primitive = raw as? JsonPrimitive

    fun bad(): Nothing = throw IllegalArgumentException("argument `$name` has the wrong shape: ${raw.toString().take(ARGUMENT_PREVIEW_CHARS)}")

    return when (parameter.type.classifier) {
        String::class -> primitive?.content ?: raw.toString()
        Int::class -> primitive?.intOrNull ?: primitive?.contentOrNull?.trim()?.toIntOrNull() ?: bad()
        Long::class -> primitive?.longOrNull ?: primitive?.contentOrNull?.trim()?.toLongOrNull() ?: bad()
        Double::class -> primitive?.doubleOrNull ?: primitive?.contentOrNull?.trim()?.toDoubleOrNull() ?: bad()
        Float::class -> (primitive?.doubleOrNull ?: primitive?.contentOrNull?.trim()?.toDoubleOrNull())?.toFloat() ?: bad()
        Boolean::class -> primitive?.booleanOrNull ?: primitive?.contentOrNull?.trim()?.toBooleanStrictOrNull() ?: bad()
        List::class -> (raw as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: it.toString() } ?: bad()
        else -> bad()
    }
}

private const val ARGUMENT_PREVIEW_CHARS = 200

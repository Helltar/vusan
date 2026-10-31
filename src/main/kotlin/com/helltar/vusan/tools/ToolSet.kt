package com.helltar.vusan.tools

import com.helltar.vusan.llm.ToolDefinition
import com.helltar.vusan.request.AttachedFile
import kotlinx.coroutines.currentCoroutineContext
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
 * Marks a [ToolSet] method as a tool the model may call, with the [description] the model reads. The method
 * must return a `String`.
 *
 * [readOnly]: the tool changes nothing (no output queued, no file written, no state a later call reads), so
 * it may run alongside the batch's other read-only calls. A lookup qualifies; a tool whose result lands in
 * the chat or sandbox does not, however harmless, because its order in the batch is its meaning.
 *
 * [copiedToSandbox]: the answer is material to work on (search results, a page, a transcript, what vision
 * saw), so its text goes into the turn's sandbox directory with the files. Opt-in: answers about other people
 * or the bot's own records, and mere confirmations, stay out of a home that outlives the turn and can be
 * published.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tool(val description: String, val readOnly: Boolean = false, val copiedToSandbox: Boolean = false)

/**
 * What the model reads about one tool argument.
 *
 * [takesReference] lets a text argument be given as a label (`#4`) or `sandbox:<path>`, and the decoder
 * passes the whole text it names. Opt-in, for bodies of text worth not retyping (a message, a script to
 * voice, file contents), so a value that merely looks like a reference elsewhere arrives as written. The
 * description tells the model so.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Arg(val description: String, val takesReference: Boolean = false)

/** A class whose [Tool] methods are offered to the model. */
interface ToolSet

/**
 * One tool as the agent runs it: its definition for the model, and the call that runs the method with the
 * model's arguments.
 *
 * Arguments are decoded by the method's parameter types: text, whole and decimal numbers, booleans and lists
 * of text (numbers spelled as text accepted), and files named by reference. Where a parameter
 * [Arg.takesReference], a reference is replaced by what it points at through the call's [CallShelf]. A
 * parameter with a default or a nullable type is optional; a missing required one or a wrong shape is an
 * [IllegalArgumentException] the agent answers the call with.
 */
class ToolFunction internal constructor(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val requiredParameters: List<String>,
    /** Whether the loop may run this alongside the batch's other read-only calls. */
    val readOnly: Boolean,
    /** Whether its answer is copied into the turn's sandbox directory. */
    val copiedToSandbox: Boolean,
    private val invoke: suspend (JsonObject) -> String,
) {

    val definition: ToolDefinition
        get() = ToolDefinition(name, description, parameters)

    suspend fun call(arguments: JsonObject): String = invoke(arguments)

    override fun toString(): String = "ToolFunction($name)"
}

/**
 * The set's tools, read off its [Tool] methods by reflection and sorted by name: the tool array is part of
 * the cached prompt prefix, so every run must send it in the same order.
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

    // a tool set may be a private class; the method is still the model's to call
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
                                // a list of files is a list of references, which are text
                                if (parameter.type.classifier == List::class) put("items", buildJsonObject { put("type", "string") })
                                parameter.findAnnotation<Arg>()?.let { put("description", it.description) }
                            },
                        )
                    }
                },
            )
            putJsonArray("required") { required.forEach { add(it) } }
        }

    return ToolFunction(name, description, schema, required, annotation.readOnly, annotation.copiedToSandbox) { arguments ->
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

private val KParameter.listElement
    get() = type.arguments.singleOrNull()?.type?.classifier

private fun KParameter.schemaType(): String? =
    when (type.classifier) {
        String::class, AttachedFile::class -> "string"
        Int::class, Long::class -> "integer"
        Double::class, Float::class -> "number"
        Boolean::class -> "boolean"
        List::class -> if (listElement == String::class || listElement == AttachedFile::class) "array" else null
        else -> null
    }

private suspend fun decodeArgument(parameter: KParameter, raw: JsonElement): Any {
    val name = parameter.parameterName
    val primitive = raw as? JsonPrimitive
    val references = currentCoroutineContext()[CallShelf]

    fun bad(): Nothing = throw IllegalArgumentException("argument `$name` has the wrong shape: ${raw.toString().take(ARGUMENT_PREVIEW_CHARS)}")

    val takesReference = parameter.findAnnotation<Arg>()?.takesReference == true

    suspend fun text(value: String): String = references?.takeIf { takesReference }?.textOrNull(value) ?: value

    suspend fun file(value: String): AttachedFile =
        requireNotNull(references) { "argument `$name` takes a file, and no file can be named here" }.file(value)

    return when (parameter.type.classifier) {
        String::class -> text(primitive?.content ?: raw.toString())
        AttachedFile::class -> file(primitive?.content ?: bad())
        Int::class -> primitive?.intOrNull ?: primitive?.contentOrNull?.trim()?.toIntOrNull() ?: bad()
        Long::class -> primitive?.longOrNull ?: primitive?.contentOrNull?.trim()?.toLongOrNull() ?: bad()
        Double::class -> primitive?.doubleOrNull ?: primitive?.contentOrNull?.trim()?.toDoubleOrNull() ?: bad()
        Float::class -> (primitive?.doubleOrNull ?: primitive?.contentOrNull?.trim()?.toDoubleOrNull())?.toFloat() ?: bad()
        Boolean::class -> primitive?.booleanOrNull ?: primitive?.contentOrNull?.trim()?.toBooleanStrictOrNull() ?: bad()
        List::class -> {
            val items = (raw as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: it.toString() } ?: bad()

            if (parameter.listElement == AttachedFile::class) items.map { file(it) } else items.map { text(it) }
        }

        else -> bad()
    }
}

private const val ARGUMENT_PREVIEW_CHARS = 200

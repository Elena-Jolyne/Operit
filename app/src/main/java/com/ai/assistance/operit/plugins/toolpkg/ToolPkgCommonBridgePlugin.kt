package com.ai.assistance.operit.plugins.toolpkg

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.ai.assistance.operit.core.chat.plugins.MessageProcessingController
import com.ai.assistance.operit.core.chat.plugins.MessageProcessingExecution
import com.ai.assistance.operit.core.chat.plugins.MessageProcessingHookParams
import com.ai.assistance.operit.core.chat.plugins.MessageProcessingPlugin
import com.ai.assistance.operit.core.chat.plugins.MessageProcessingPluginRegistry
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_INPUT_MENU_TOGGLE
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_MESSAGE_PROCESSING
import com.ai.assistance.operit.core.tools.packTool.TOOLPKG_EVENT_XML_RENDER
import com.ai.assistance.operit.plugins.OperitPlugin
import com.ai.assistance.operit.ui.common.markdown.XmlRenderPlugin
import com.ai.assistance.operit.ui.common.markdown.XmlRenderPluginRegistry
import com.ai.assistance.operit.ui.common.markdown.XmlRenderResult
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.InputMenuToggleDefinition
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.InputMenuToggleHookParams
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.InputMenuTogglePlugin
import com.ai.assistance.operit.ui.features.chat.components.style.input.common.InputMenuTogglePluginRegistry
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.stream.Stream
import com.ai.assistance.operit.core.tools.packTool.ToolPkgXmlRenderHookComposeDslResult
import com.ai.assistance.operit.core.tools.packTool.ToolPkgXmlRenderHookObjectResult
import com.ai.assistance.operit.util.stream.asStream
import com.ai.assistance.operit.util.stream.streamOf
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private const val TAG = "ToolPkgCommonBridge"

private fun packageManager(context: Context): PackageManager {
    return PackageManager.getInstance(context, AIToolHandler.getInstance(context))
}

private fun decodeHookResult(raw: Any?): Any? {
    val text = raw?.toString()?.trim().orEmpty()
    if (text.isEmpty()) {
        return null
    }
    if (text.startsWith("Error:", ignoreCase = true)) {
        throw IllegalStateException(text.substringAfter(":", text).trim().ifEmpty { text })
    }
    return try {
        JSONTokener(text).nextValue()
    } catch (_: Exception) {
        text
    }
}

private object ToolPkgMessageProcessingBridgePlugin : MessageProcessingPlugin {
    override val id: String = "builtin.toolpkg.message-processing-bridge"

    override suspend fun createExecutionIfMatched(
        params: MessageProcessingHookParams
    ): MessageProcessingExecution? {
        val manager = packageManager(params.context)
        val hooks =
            withContext(Dispatchers.IO) {
                manager.getToolPkgMessageProcessingPlugins()
            }
        for (hook in hooks) {
            val result =
                withContext(Dispatchers.IO) {
                    manager.runToolPkgMainHook(
                        containerPackageName = hook.containerPackageName,
                        functionName = hook.functionName,
                        event = TOOLPKG_EVENT_MESSAGE_PROCESSING,
                        pluginId = hook.pluginId,
                        eventPayload =
                            mapOf(
                                "messageContent" to params.messageContent,
                                "chatHistory" to params.chatHistory.map { listOf(it.first, it.second) },
                                "workspacePath" to params.workspacePath,
                                "maxTokens" to params.maxTokens,
                                "tokenUsageThreshold" to params.tokenUsageThreshold
                            )
                    )
                }
            val value =
                result.getOrElse { error ->
                    AppLogger.e(
                        TAG,
                        "ToolPkg message processing hook failed: ${hook.containerPackageName}:${hook.pluginId}",
                        error
                    )
                    return@getOrElse null
                } ?: continue

            val decoded =
                runCatching { decodeHookResult(value) }
                    .getOrElse { error ->
                        AppLogger.e(
                            TAG,
                            "ToolPkg message processing hook decode failed: ${hook.containerPackageName}:${hook.pluginId}",
                            error
                        )
                        null
                    }
            val execution = toMessageProcessingExecution(decoded)
            if (execution != null) {
                return execution
            }
        }
        return null
    }

    private fun toMessageProcessingExecution(decoded: Any?): MessageProcessingExecution? {
        return when (decoded) {
            null -> null
            is Boolean -> if (decoded) MessageProcessingExecution(NoopMessageController, streamOf("")) else null
            is String ->
                if (decoded.isBlank()) null
                else MessageProcessingExecution(NoopMessageController, streamOf(decoded))
            is JSONObject -> {
                val matched = decoded.optBoolean("matched", true)
                if (!matched) {
                    return null
                }
                val chunks = mutableListOf<String>()
                val chunksArray = decoded.optJSONArray("chunks")
                if (chunksArray != null) {
                    for (index in 0 until chunksArray.length()) {
                        val chunk = chunksArray.optString(index).trim()
                        if (chunk.isNotEmpty()) {
                            chunks.add(chunk)
                        }
                    }
                }
                val text = decoded.optString("text").ifBlank { decoded.optString("content") }.trim()
                if (text.isNotEmpty()) {
                    chunks.add(text)
                }
                val stream: Stream<String> =
                    when {
                        chunks.isEmpty() -> streamOf("")
                        chunks.size == 1 -> streamOf(chunks.first())
                        else -> chunks.asStream()
                    }
                MessageProcessingExecution(NoopMessageController, stream)
            }
            else -> null
        }
    }
}

private object NoopMessageController : MessageProcessingController {
    override fun cancel() = Unit
}

private object ToolPkgXmlRenderBridgePlugin : XmlRenderPlugin {
    override val id: String = "builtin.toolpkg.xml-render-bridge"

    override fun supports(tagName: String): Boolean {
        return true
    }

    override suspend fun resolve(
        context: Context,
        xmlContent: String,
        tagName: String,
        textColor: Color,
        xmlStream: Stream<String>?
    ): XmlRenderResult? {
        val manager = packageManager(context)
        val hooks =
            withContext(Dispatchers.IO) {
                manager.getToolPkgXmlRenderPlugins(tagName)
            }
        for (hook in hooks) {
            val result =
                withContext(Dispatchers.IO) {
                    manager.runToolPkgMainHook(
                        containerPackageName = hook.containerPackageName,
                        functionName = hook.functionName,
                        event = TOOLPKG_EVENT_XML_RENDER,
                        pluginId = hook.pluginId,
                        eventPayload =
                            mapOf(
                                "xmlContent" to xmlContent,
                                "tagName" to tagName
                            )
                    )
                }
            val value =
                result.getOrElse { error ->
                    AppLogger.e(
                        TAG,
                        "ToolPkg xml render hook failed: ${hook.containerPackageName}:${hook.pluginId}",
                        error
                    )
                    return@getOrElse null
                } ?: continue
            val decoded =
                runCatching { decodeHookResult(value) }
                    .getOrElse { error ->
                        AppLogger.e(
                            TAG,
                            "ToolPkg xml render hook decode failed: ${hook.containerPackageName}:${hook.pluginId}",
                            error
                        )
                        null
                    }
            val parsed = parseXmlRenderHookObjectResult(decoded) ?: continue
            if (parsed.handled == false) {
                continue
            }
            val composeDsl = parsed.composeDsl
            if (composeDsl != null) {
                return XmlRenderResult.ComposeDslScreen(
                    containerPackageName = hook.containerPackageName,
                    screenPath = composeDsl.screen,
                    state = composeDsl.state,
                    memo = composeDsl.memo,
                    moduleSpec = composeDsl.moduleSpec
                )
            }
            val text = parsed.text?.ifBlank { parsed.content.orEmpty() }?.trim().orEmpty()
            if (text.isNotBlank()) {
                return XmlRenderResult.Text(text)
            }
        }
        return null
    }

    private fun parseXmlRenderHookObjectResult(decoded: Any?): ToolPkgXmlRenderHookObjectResult? {
        return when (decoded) {
            null -> null
            is String -> {
                val text = decoded.trim()
                if (text.isBlank()) null else ToolPkgXmlRenderHookObjectResult(handled = true, text = text)
            }
            is JSONObject -> {
                val handled = decoded.optBoolean("handled", true)
                val text = decoded.optString("text").ifBlank { decoded.optString("content") }.trim()
                val composeDslRaw = decoded.opt("composeDsl")
                val composeDsl = parseComposeDslResult(composeDslRaw)
                ToolPkgXmlRenderHookObjectResult(
                    handled = handled,
                    text = text.ifBlank { null },
                    content = decoded.optString("content").trim().ifBlank { null },
                    composeDsl = composeDsl
                )
            }
            else -> null
        }
    }

    private fun parseComposeDslResult(raw: Any?): ToolPkgXmlRenderHookComposeDslResult? {
        val map =
            when (raw) {
                is JSONObject -> raw
                is Map<*, *> -> JSONObject(raw)
                else -> null
            } ?: return null

        val screen = map.optString("screen").trim()
        if (screen.isBlank()) {
            return null
        }

        val state = asMap(map.opt("state"))
        val memo = asMap(map.opt("memo"))
        val moduleSpec = asMap(map.opt("moduleSpec"))

        return ToolPkgXmlRenderHookComposeDslResult(
            screen = screen,
            state = state,
            memo = memo,
            moduleSpec = if (moduleSpec.isNotEmpty()) moduleSpec else null
        )
    }

    private fun asMap(value: Any?): Map<String, Any?> {
        return when (value) {
            is JSONObject -> {
                val map = linkedMapOf<String, Any?>()
                value.keys().forEach { key ->
                    map[key] = normalizeValue(value.opt(key))
                }
                map
            }
            is Map<*, *> -> {
                value.entries.associate { entry ->
                    entry.key.toString() to normalizeValue(entry.value)
                }
            }
            else -> emptyMap()
        }
    }

    private fun asList(value: Any?): List<Any?> {
        return when (value) {
            is JSONArray -> {
                buildList {
                    for (index in 0 until value.length()) {
                        add(normalizeValue(value.opt(index)))
                    }
                }
            }
            is List<*> -> value.map { normalizeValue(it) }
            else -> emptyList()
        }
    }

    private fun normalizeValue(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> asMap(value)
            is JSONArray -> asList(value)
            else -> value
        }
    }
}

private object ToolPkgInputMenuToggleBridgePlugin : InputMenuTogglePlugin {
    override val id: String = "builtin.toolpkg.input-menu-toggle-bridge"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val hooksLock = Any()
    @Volatile
    private var hooksCache: List<PackageManager.ToolPkgInputMenuTogglePlugin>? = null
    @Volatile
    private var specsCache: List<InputMenuSpec> = emptyList()
    private val refreshFlag = AtomicBoolean(false)

    override fun createToggles(
        params: InputMenuToggleHookParams
    ): List<InputMenuToggleDefinition> {
        triggerRefresh(params = params)
        val cachedSpecs = specsCache
        if (cachedSpecs.isEmpty()) {
            return emptyList()
        }
        return cachedSpecs.map { spec ->
            val resolvedChecked = params.featureStates[spec.id] ?: spec.isChecked
            InputMenuToggleDefinition(
                id = spec.id,
                title = spec.title,
                description = spec.description,
                isChecked = resolvedChecked,
                onToggle = {
                    if (params.featureStates.containsKey(spec.id)) {
                        params.onToggleFeature(spec.id)
                        return@InputMenuToggleDefinition
                    }
                    scope.launch {
                        val manager = packageManager(params.context)
                        manager.runToolPkgMainHook(
                            containerPackageName = spec.containerPackageName,
                            functionName = spec.functionName,
                            event = TOOLPKG_EVENT_INPUT_MENU_TOGGLE,
                            pluginId = spec.pluginId,
                            eventPayload =
                                mapOf(
                                    "action" to "toggle",
                                    "toggleId" to spec.id
                                )
                        )
                    }
                }
            )
        }
    }

    private fun triggerRefresh(params: InputMenuToggleHookParams) {
        if (!refreshFlag.compareAndSet(false, true)) {
            return
        }
        scope.launch {
            try {
                val manager = packageManager(params.context)
                val hooks = getCachedHooks(manager)
                val resolved = mutableListOf<InputMenuSpec>()
                hooks.forEach { hook ->
                    val result =
                        manager.runToolPkgMainHook(
                            containerPackageName = hook.containerPackageName,
                            functionName = hook.functionName,
                            event = TOOLPKG_EVENT_INPUT_MENU_TOGGLE,
                            pluginId = hook.pluginId,
                            eventPayload =
                                mapOf(
                                    "action" to "create"
                                )
                        )
                    val value =
                        result.getOrElse { error ->
                            AppLogger.e(
                                TAG,
                                "ToolPkg input menu hook failed: ${hook.containerPackageName}:${hook.pluginId}",
                                error
                            )
                            return@getOrElse null
                        } ?: return@forEach
                    val decoded =
                        runCatching { decodeHookResult(value) }
                            .getOrElse { error ->
                                AppLogger.e(
                                    TAG,
                                    "ToolPkg input menu hook decode failed: ${hook.containerPackageName}:${hook.pluginId}",
                                    error
                                )
                                null
                            }
                    resolved.addAll(
                        parseInputMenuDefinitions(
                            decoded = decoded,
                            containerPackageName = hook.containerPackageName,
                            functionName = hook.functionName,
                            pluginId = hook.pluginId
                        )
                    )
                }
                specsCache = resolved
            } finally {
                refreshFlag.set(false)
            }
        }
    }

    private fun getCachedHooks(manager: PackageManager): List<PackageManager.ToolPkgInputMenuTogglePlugin> {
        hooksCache?.let { return it }
        synchronized(hooksLock) {
            hooksCache?.let { return it }
            val loaded = manager.getToolPkgInputMenuTogglePlugins()
            hooksCache = loaded
            return loaded
        }
    }

    private data class InputMenuSpec(
        val containerPackageName: String,
        val functionName: String,
        val pluginId: String,
        val id: String,
        val title: String,
        val description: String,
        val isChecked: Boolean
    )

    private fun parseInputMenuDefinitions(
        decoded: Any?,
        containerPackageName: String,
        functionName: String,
        pluginId: String
    ): List<InputMenuSpec> {
        val array =
            when (decoded) {
                is JSONArray -> decoded
                is JSONObject -> decoded.optJSONArray("toggles")
                else -> null
            } ?: return emptyList()

        val specs = mutableListOf<InputMenuSpec>()
        for (index in 0 until array.length()) {
            val item = array.opt(index) as? JSONObject ?: continue
            val id = item.optString("id").trim()
            val title = item.optString("title").trim()
            if (id.isBlank() || title.isBlank()) {
                continue
            }
            specs.add(
                InputMenuSpec(
                    containerPackageName = containerPackageName,
                    functionName = functionName,
                    pluginId = pluginId,
                    id = id,
                    title = title,
                    description = item.optString("description").trim(),
                    isChecked = item.optBoolean("isChecked", false)
                )
            )
        }
        return specs
    }
}

object ToolPkgCommonBridgePlugin : OperitPlugin {
    override val id: String = "builtin.toolpkg.common-bridge"

    override fun register() {
        MessageProcessingPluginRegistry.register(ToolPkgMessageProcessingBridgePlugin)
        XmlRenderPluginRegistry.register(ToolPkgXmlRenderBridgePlugin)
        InputMenuTogglePluginRegistry.register(ToolPkgInputMenuToggleBridgePlugin)
    }
}

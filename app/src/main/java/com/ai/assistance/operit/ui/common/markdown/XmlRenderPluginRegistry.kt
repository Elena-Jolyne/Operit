package com.ai.assistance.operit.ui.common.markdown

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.javascript.JsEngine
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import com.ai.assistance.operit.core.tools.packTool.ToolPkgComposeDslParser
import com.ai.assistance.operit.core.tools.packTool.ToolPkgComposeDslRenderResult
import com.ai.assistance.operit.ui.common.composedsl.RenderToolPkgComposeDslNode
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.LocaleUtils
import com.ai.assistance.operit.util.stream.Stream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context

sealed class XmlRenderResult {
    data class ComposableRender(
        val render: @Composable (Modifier, Color, Stream<String>?) -> Unit
    ) : XmlRenderResult()

    data class Text(val text: String) : XmlRenderResult()

    data class ComposeDslScreen(
        val containerPackageName: String,
        val screenPath: String,
        val state: Map<String, Any?> = emptyMap(),
        val memo: Map<String, Any?> = emptyMap(),
        val moduleSpec: Map<String, Any?>? = null
    ) : XmlRenderResult()
}

interface XmlRenderPlugin {
    val id: String

    fun supports(tagName: String): Boolean

    suspend fun resolve(
        context: Context,
        xmlContent: String,
        tagName: String,
        textColor: Color,
        xmlStream: Stream<String>?
    ): XmlRenderResult?
}

object XmlRenderPluginRegistry {
    private const val TAG = "XmlRenderPluginRegistry"
    private val plugins = CopyOnWriteArrayList<XmlRenderPlugin>()

    @Synchronized
    fun register(plugin: XmlRenderPlugin) {
        unregister(plugin.id)
        plugins.add(plugin)
    }

    @Synchronized
    fun unregister(pluginId: String) {
        plugins.removeAll { it.id == pluginId }
    }

    @Composable
    fun RenderIfMatched(
        xmlContent: String,
        tagName: String,
        modifier: Modifier,
        textColor: Color,
        xmlStream: Stream<String>?
    ): Boolean {
        val plugin = plugins.firstOrNull { it.supports(tagName) } ?: return false
        val context = LocalContext.current
        var result by remember(xmlContent, tagName, plugin.id) { mutableStateOf<XmlRenderResult?>(null) }
        var errorMessage by remember(xmlContent, tagName, plugin.id) { mutableStateOf<String?>(null) }

        LaunchedEffect(xmlContent, tagName, plugin.id) {
            runCatching {
                plugin.resolve(
                    context = context,
                    xmlContent = xmlContent,
                    tagName = tagName,
                    textColor = textColor,
                    xmlStream = xmlStream
                )
            }.onSuccess { resolved ->
                result = resolved
                errorMessage = null
            }.onFailure { error ->
                result = null
                errorMessage = error.message
                AppLogger.e(TAG, "Xml render plugin failed: ${plugin.id}", error)
            }
        }

        when (val resolved = result) {
            is XmlRenderResult.ComposableRender -> {
                resolved.render(modifier, textColor, xmlStream)
                return true
            }
            is XmlRenderResult.Text -> {
                Text(
                    text = resolved.text,
                    color = textColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = Int.MAX_VALUE,
                    overflow = TextOverflow.Clip,
                    modifier = modifier
                )
                return true
            }
            is XmlRenderResult.ComposeDslScreen -> {
                RenderComposeDslScreen(
                    result = resolved,
                    modifier = modifier
                )
                return true
            }
            null -> {
                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage.orEmpty(),
                        color = textColor,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = modifier
                    )
                    return true
                }
                return true
            }
        }
    }

    @Composable
    private fun RenderComposeDslScreen(
        result: XmlRenderResult.ComposeDslScreen,
        modifier: Modifier
    ) {
        val context = LocalContext.current
        val packageManager = remember(result.containerPackageName) {
            PackageManager.getInstance(context, AIToolHandler.getInstance(context))
        }
        val jsEngine = remember(result.containerPackageName, result.screenPath) { JsEngine(context) }
        DisposableEffect(jsEngine) {
            onDispose { jsEngine.destroy() }
        }

        var renderResult by remember(result.containerPackageName, result.screenPath) {
            mutableStateOf<ToolPkgComposeDslRenderResult?>(null)
        }
        var errorMessage by remember(result.containerPackageName, result.screenPath) {
            mutableStateOf<String?>(null)
        }
        var isLoading by remember(result.containerPackageName, result.screenPath) {
            mutableStateOf(true)
        }
        var isDispatching by remember(result.containerPackageName, result.screenPath) {
            mutableStateOf(false)
        }

        fun buildModuleSpec(screenPath: String): Map<String, Any?> {
            val provided = result.moduleSpec
            if (provided != null && provided.isNotEmpty()) {
                return provided
            }
            return mapOf(
                "id" to "xml_render",
                "runtime" to "compose_dsl",
                "screen" to screenPath,
                "title" to screenPath,
                "toolPkgId" to result.containerPackageName
            )
        }

        fun dispatchAction(actionId: String, payload: Any?) {
            val normalizedActionId = actionId.trim()
            if (normalizedActionId.isBlank()) {
                return
            }
            val silent =
                (payload as? Map<*, *>)?.get("__silent") as? Boolean ?: false
            if (!silent) {
                isDispatching = true
            }
            jsEngine.dispatchComposeDslActionAsync(
                actionId = normalizedActionId,
                payload = payload,
                onIntermediateResult = { intermediateResult ->
                    val parsed = ToolPkgComposeDslParser.parseRenderResult(intermediateResult)
                    if (parsed != null) {
                        renderResult = parsed
                        errorMessage = null
                    }
                },
                onComplete = {
                    if (!silent) {
                        isDispatching = false
                    }
                },
                onError = { error ->
                    errorMessage = "compose_dsl runtime error: $error"
                    AppLogger.e(TAG, "compose_dsl action failed: $error")
                    if (!silent) {
                        isDispatching = false
                    }
                }
            )
        }

        LaunchedEffect(result.containerPackageName, result.screenPath, result.state, result.memo) {
            isLoading = true
            errorMessage = null
            val screenPath = result.screenPath.trim()
            if (screenPath.isBlank()) {
                errorMessage = "compose_dsl screen path is blank"
                isLoading = false
                return@LaunchedEffect
            }
            val script =
                withContext(Dispatchers.IO) {
                    packageManager.readToolPkgTextResource(
                        packageNameOrSubpackageId = result.containerPackageName,
                        resourcePath = screenPath
                    )
                }
            if (script.isNullOrBlank()) {
                errorMessage = "compose_dsl screen not found: ${result.containerPackageName}:$screenPath"
                isLoading = false
                return@LaunchedEffect
            }

            val language = LocaleUtils.getCurrentLanguage(context).trim()
            val rawResult =
                withContext(Dispatchers.IO) {
                    jsEngine.executeComposeDslScript(
                        script = script,
                        runtimeOptions =
                            mapOf(
                                "packageName" to result.containerPackageName,
                                "toolPkgId" to result.containerPackageName,
                                "uiModuleId" to "xml_render",
                                "__operit_package_lang" to (if (language.isNotBlank()) language else "zh"),
                                "__operit_script_screen" to screenPath,
                                "moduleSpec" to buildModuleSpec(screenPath),
                                "state" to result.state,
                                "memo" to result.memo
                            )
                    )
                }

            val parsed = ToolPkgComposeDslParser.parseRenderResult(rawResult)
            if (parsed == null) {
                val rawText = rawResult?.toString()?.trim().orEmpty()
                errorMessage =
                    if (rawText.isNotBlank()) "Invalid compose_dsl result: $rawText" else "Invalid compose_dsl result"
                renderResult = null
            } else {
                renderResult = parsed
                errorMessage = null
                val onLoadActionId = ToolPkgComposeDslParser.extractActionId(parsed.tree.props["onLoad"])
                if (!onLoadActionId.isNullOrBlank()) {
                    dispatchAction(onLoadActionId, null)
                }
            }
            isLoading = false
        }

        Box(modifier = modifier) {
            when {
                isLoading -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                errorMessage != null -> {
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp)
                    )
                }
                renderResult?.tree != null -> {
                    RenderToolPkgComposeDslNode(
                        node = renderResult!!.tree,
                        modifier = Modifier.align(Alignment.TopStart),
                        onAction = ::dispatchAction
                    )
                }
            }
            if (isDispatching) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

package com.ai.assistance.operit.core.tools.javascript

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.packTool.PackageManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.json.JSONTokener
import android.graphics.Bitmap
import com.ai.assistance.operit.core.application.ActivityLifecycleManager
import com.ai.assistance.operit.core.tools.javascript.JsTimeoutConfig
import com.ai.assistance.operit.util.ImagePoolManager
import com.ai.assistance.operit.util.OperitPaths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray

/**
 * JavaScript 引擎 - 通过 WebView 执行 JavaScript 脚本 提供与 Android 原生代码的交互机制
 *
 * 主要功能：
 * 1. 执行 JavaScript 脚本
 * 2. 为脚本提供工具调用能力
 * 3. 集成常用的第三方 JavaScript 库
 *
 * 工具调用使用方式:
 * - 标准模式: toolCall("toolType", "toolName", { param1: "value1" })
 * - 简化模式: toolCall("toolName", { param1: "value1" })
 * - 对象模式: toolCall({ type: "toolType", name: "toolName", params: { param1: "value1" } })
 * - 直接模式: toolCall("toolName")
 *
 * 便捷工具调用:
 * - 文件操作: Tools.Files.read("/path/to/file")
 * - 网络操作: Tools.Net.httpGet("https://example.com")
 * - 系统操作: Tools.System.sleep("1")
 * - 计算功能: Tools.calc("2 + 2 * 3")
 *
 * 完成脚本执行:
 * - complete(result) 函数传递最终结果返回给调用者
 */
class JsEngine(private val context: Context) {
    companion object {
        private const val TAG = "JsEngine"
        private const val TOOLPKG_TAG = "Toolpkg"
        private const val BINARY_DATA_THRESHOLD = 32 * 1024 // 32KB
        private const val BINARY_HANDLE_PREFIX = "@binary_handle:"
    }

    // 存储原生Bitmap对象的注册表
    private val bitmapRegistry = ConcurrentHashMap<String, Bitmap>()
    // 存储大型二进制数据的注册表
    private val binaryDataRegistry = ConcurrentHashMap<String, ByteArray>()
    // 存储 Java/Kotlin 桥接对象实例
    private val javaObjectRegistry = ConcurrentHashMap<String, Any>()

    // WebView 实例用于执行 JavaScript
    private var webView: WebView? = null

    // 工具处理器
    private val toolHandler = AIToolHandler.getInstance(context)
    private val packageManager by lazy { PackageManager.getInstance(context, toolHandler) }

    // 工具调用接口
    private val toolCallInterface = JsToolCallInterface()

    // 结果回调
    @Volatile
    private var resultCallback: CompletableFuture<Any?>? = null
    @Volatile
    private var intermediateResultCallback: ((Any?) -> Unit)? = null
    // A single WebView cannot safely run multiple scripts concurrently.
    // This lock enforces per-engine serialization; cross-context concurrency must use different JsEngine instances.
    private val executionLock = java.util.concurrent.locks.ReentrantLock()

    // 用于存储工具调用的回调
    private val toolCallbacks = mutableMapOf<String, CompletableFuture<String>>()
    private val composeDslActionCompleteCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val composeDslActionErrorCallbacks = ConcurrentHashMap<String, (String) -> Unit>()

    // 标记 JS 环境是否已初始化
    private var jsEnvironmentInitialized = false

    private var envOverrides: Map<String, String> = emptyMap()
    private val toolPkgExecutionContext = JsToolPkgExecutionContext()
    private val toolPkgRegistrationSession = JsToolPkgRegistrationSession()

    fun <T> withTemporaryToolPkgTextResourceResolver(
        resolver: (String, String) -> String?,
        block: () -> T
    ): T {
        return toolPkgExecutionContext.withTemporaryTextResourceResolver(resolver, block)
    }

    private fun resolveTemporaryToolPkgTextResource(
        packageNameOrSubpackageId: String,
        resourcePath: String
    ): String? {
        return toolPkgExecutionContext.resolveTemporaryTextResource(
            packageNameOrSubpackageId = packageNameOrSubpackageId,
            resourcePath = resourcePath,
            onResolverFailure = { e ->
                AppLogger.e(
                    TAG,
                    "Temporary toolpkg text resource resolver failed: package/subpackage=$packageNameOrSubpackageId, path=$resourcePath",
                    e
                )
            }
        )
    }

    private fun hasTemporaryToolPkgTextResourceResolver(): Boolean {
        return toolPkgExecutionContext.hasTemporaryTextResourceResolver()
    }

    // 初始化 WebView
    private fun initWebView() {
        if (webView == null) {
            // 需要在主线程创建 WebView
            val latch = CountDownLatch(1)
            ContextCompat.getMainExecutor(context).execute {
                try {
                    webView =
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true // 允许访问 sessionStorage 和 localStorage

                                // 为了安全，禁用文件系统访问，除非显式通过工具提供
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false

                                // 设置User Agent
                                settings.userAgentString = "Operit-JsEngine/1.0"
                                addJavascriptInterface(toolCallInterface, "NativeInterface")
                                // 加载一个带有有效基地址的空HTML页面，以解决 about:blank 的源安全问题
                                loadDataWithBaseURL("https://localhost", "<html></html>", "text/html", "UTF-8", null)
                            }
                    latch.countDown()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error initializing WebView: ${e.message}", e)
                    latch.countDown()
                }
            }
            latch.await(10, TimeUnit.SECONDS)
        }
    }

    private fun getComposeDslContextBridgeDefinition(): String {
        return buildComposeDslContextBridgeDefinition()
    }

    private fun getJavaClassBridgeDefinition(): String {
        return buildJavaClassBridgeDefinition()
    }

    private fun getJavaBridgeClassLoader(): ClassLoader {
        return context.classLoader
            ?: this::class.java.classLoader
            ?: ClassLoader.getSystemClassLoader()
    }

    private fun decodeEvaluateJavascriptResult(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") {
            return ""
        }
        return try {
            val token = JSONTokener(raw).nextValue()
            when (token) {
                is String -> token
                else -> token?.toString().orEmpty()
            }
        } catch (_: Exception) {
            raw
        }
    }

    private fun withToolpkgPluginTag(message: String): String {
        return toolPkgExecutionContext.withPluginTag(message)
    }

    private fun withToolpkgCodeContext(message: String): String {
        return toolPkgExecutionContext.withCodeContext(message)
    }

    private fun invokeJavaBridgeJsObjectCallbackSync(
        jsObjectId: String,
        methodName: String,
        argsJson: String
    ): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return JSONObject()
                .put("success", false)
                .put("error", "java bridge callback cannot synchronously invoke JS on main thread")
                .toString()
        }

        val targetWebView = webView
        if (targetWebView == null) {
            return JSONObject()
                .put("success", false)
                .put("error", "webview is not initialized")
                .toString()
        }

        val safeArgsJson = argsJson.trim().ifEmpty { "[]" }
        val callbackScript =
            """
            (function() {
                try {
                    var __invoker =
                        (typeof globalThis !== 'undefined' && typeof globalThis.__operitJavaBridgeInvokeJsObject === 'function')
                            ? globalThis.__operitJavaBridgeInvokeJsObject
                            : undefined;
                    if (!__invoker) {
                        return JSON.stringify({
                            success: false,
                            error: 'java bridge js callback runtime unavailable'
                        });
                    }
                    var __result = __invoker(
                        ${JSONObject.quote(jsObjectId)},
                        ${JSONObject.quote(methodName)},
                        $safeArgsJson
                    );
                    return JSON.stringify({
                        success: true,
                        data: __result
                    });
                } catch (e) {
                    return JSON.stringify({
                        success: false,
                        error: (e && e.message) ? e.message : String(e)
                    });
                }
            })();
            """.trimIndent()

        val latch = CountDownLatch(1)
        var decodedResult: String? = null

        ContextCompat.getMainExecutor(context).execute {
            try {
                targetWebView.evaluateJavascript(callbackScript) { raw ->
                    decodedResult = decodeEvaluateJavascriptResult(raw)
                    latch.countDown()
                }
            } catch (e: Exception) {
                decodedResult =
                    JSONObject()
                        .put("success", false)
                        .put("error", "java bridge callback evaluation failed: ${e.message}")
                        .toString()
                latch.countDown()
            }
        }

        return try {
            if (!latch.await(6, TimeUnit.SECONDS)) {
                JSONObject()
                    .put("success", false)
                    .put("error", "java bridge callback timed out")
                    .toString()
            } else {
                decodedResult
                    ?: JSONObject()
                        .put("success", false)
                        .put("error", "java bridge callback returned empty result")
                        .toString()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            JSONObject()
                .put("success", false)
                .put("error", "java bridge callback interrupted: ${e.message}")
                .toString()
        }
    }

    private fun splitBridgeResult(raw: String): Pair<String?, Any?> {
        if (raw.isBlank()) {
            return Pair("empty bridge response", null)
        }
        return try {
            val token = JSONTokener(raw).nextValue()
            if (token is JSONObject) {
                val success = token.optBoolean("success", false)
                val data = token.opt("data")
                val error = token.optString("error").ifBlank { null }
                if (success) {
                    Pair(null, data)
                } else {
                    Pair(error ?: "bridge call failed", null)
                }
            } else {
                Pair("invalid bridge response format", null)
            }
        } catch (e: Exception) {
            Pair("failed to parse bridge response: ${e.message}", null)
        }
    }

    /** 初始化 JavaScript 环境 加载核心功能、工具库和辅助函数 这些代码只需要执行一次 */
    private fun initJavaScriptEnvironment() {
        if (jsEnvironmentInitialized) {
            return // 如果已经初始化，直接返回
        }

        val operitDownloadDir = OperitPaths.operitRootPathSdcard()
        val operitCleanOnExitDir = OperitPaths.cleanOnExitPathSdcard()

        val initScript =
            buildInitRuntimeScript(
                operitDownloadDir = operitDownloadDir,
                operitCleanOnExitDir = operitCleanOnExitDir,
                asyncPromiseTimeoutSeconds = JsTimeoutConfig.ASYNC_PROMISE_TIMEOUT_SECONDS,
                toolPkgRegistrationBridgeScript = buildToolPkgRegistrationBridgeScript(),
                jsToolsDefinition = getJsToolsDefinition(),
                composeDslContextBridgeDefinition = getComposeDslContextBridgeDefinition(),
                javaClassBridgeDefinition = getJavaClassBridgeDefinition(),
                jsThirdPartyLibraries = getJsThirdPartyLibraries(),
                cryptoJsBridgeScript = loadCryptoJs(context),
                jimpJsBridgeScript = loadJimpJs(context),
                uiNodeJsScript = loadUINodeJs(context),
                androidUtilsJsScript = loadAndroidUtilsJs(context),
                okHttp3JsScript = loadOkHttp3Js(context),
                pakoJsBridgeScript = loadPakoJs(context)
            )

        // 在 WebView 中执行初始化脚本
        val initLatch = CountDownLatch(1)
        ContextCompat.getMainExecutor(context).execute {
            try {
                webView?.evaluateJavascript(initScript) { result ->
                    AppLogger.d(TAG, "JS environment initialization completed: $result")
                    try {
                        webView?.evaluateJavascript("typeof __handleAsync === 'function'") { checkResult ->
                            val isHandleAsyncDefined = checkResult == "true"
                            if (isHandleAsyncDefined) {
                                jsEnvironmentInitialized = true
                            } else {
                                jsEnvironmentInitialized = false
                                AppLogger.e(TAG, "__handleAsync is not defined after JS environment initialization. Result: $checkResult")
                            }
                            initLatch.countDown()
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to verify __handleAsync after JS environment initialization: ${e.message}", e)
                        jsEnvironmentInitialized = false
                        initLatch.countDown()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to initialize JS environment: ${e.message}", e)
                jsEnvironmentInitialized = false
                initLatch.countDown()
            }
        }

        // 等待初始化完成，使用超时避免无限等待
        try {
            if (!initLatch.await(10, TimeUnit.SECONDS)) {
                AppLogger.w(TAG, "JS environment initialization timeout after 10 seconds")
            }
        } catch (e: InterruptedException) {
            AppLogger.e(TAG, "JS environment initialization interrupted", e)
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 执行 JavaScript 脚本并调用其中的特定函数
     * @param script 完整的JavaScript脚本内容
     * @param functionName 要调用的函数名称
     * @param params 要传递给函数的参数
     * @return 函数执行结果
     */
    fun executeScriptFunction(
            script: String,
            functionName: String,
            params: Map<String, Any?>,
            envOverrides: Map<String, String> = emptyMap(),
            onIntermediateResult: ((Any?) -> Unit)? = null,
            timeoutSec: Long = JsTimeoutConfig.MAIN_TIMEOUT_SECONDS.toLong()
    ): Any? {
        executionLock.lock()
        try {
        // Reset any previous state
        resetState()
        this.envOverrides = envOverrides
        this.intermediateResultCallback = onIntermediateResult
        this.toolPkgExecutionContext.begin(script, functionName, params)

        initWebView()

        // 确保JavaScript环境已初始化
        if (!jsEnvironmentInitialized) {
            initJavaScriptEnvironment()
        }

        val future = CompletableFuture<Any?>()
        resultCallback = future

        // 将参数转换为 JSON 对象
        val paramsJson = JSONObject(params).toString()
        val scriptJson = JSONObject.quote(script)
        val functionNameJson = JSONObject.quote(functionName)

        // 优化后的脚本执行代码，只包含必要的执行逻辑
        // 执行脚本内容由独立构建器负责，避免在 JsEngine 中堆叠长字符串逻辑
        val executionScript =
            buildExecutionScript(
                paramsJson = paramsJson,
                scriptJson = scriptJson,
                functionNameJson = functionNameJson,
                timeoutSec = timeoutSec,
                preTimeoutSeconds = JsTimeoutConfig.PRE_TIMEOUT_SECONDS
            )

        // 在主线程中执行脚本
        ContextCompat.getMainExecutor(context).execute {
            webView?.evaluateJavascript(executionScript) { result ->
                AppLogger.d(TAG, "Script execution dispatched. Sync result: $result (final async result is handled separately)")
            }
        }

        // 等待结果或超时
        return try {
            // 创建一个定时器，在超时前提醒JavaScript
            val preTimeoutTimer = java.util.Timer()

            // 只在较长的脚本执行中使用超时预警
            preTimeoutTimer.schedule(
                    object : java.util.TimerTask() {
                        override fun run() {
                            try {
                                // 如果还没完成，尝试提前触发完成
                                if (!future.isDone) {
                                    AppLogger.d(TAG, "Pre-timeout warning triggered")
                                    ContextCompat.getMainExecutor(context).execute {
                                        webView?.evaluateJavascript(
                                                """
                                    if (typeof complete === 'function' && !window._hasCompleted) {
                                        console.log("Script execution approaching timeout");
                                        // 不强制完成，只记录警告
                                    }
                                """,
                                                null
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Error in pre-timeout handler: ${e.message}", e)
                            }
                        }
                    },
                    JsTimeoutConfig.PRE_TIMEOUT_SECONDS * 1000
            )

            try {
                val safeTimeoutSec = if (timeoutSec <= 0L) 1L else timeoutSec
                val result = future.get(safeTimeoutSec, TimeUnit.SECONDS)
                preTimeoutTimer.cancel()
                result
            } catch (e: Exception) {
                preTimeoutTimer.cancel()
                throw e
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Script execution timed out or failed: ${e.message}", e)
            // 确保WebView的JavaScript不再继续执行
            ContextCompat.getMainExecutor(context).execute {
                webView?.evaluateJavascript(
                        "window._hasCompleted = true; clearTimeout(window._safetyTimeout);",
                        null
                )
            }
            val failureReason =
                when (e) {
                    is java.util.concurrent.TimeoutException ->
                        "Script execution timed out after ${if (timeoutSec <= 0L) 1L else timeoutSec} seconds"
                    else -> e.message ?: e.javaClass.simpleName
            }
            "Error: $failureReason"
        }
        } finally {
            executionLock.unlock()
        }
    }

    fun executeToolPkgMainRegistrationFunction(
        script: String,
        functionName: String,
        params: Map<String, Any?> = emptyMap()
    ): ToolPkgMainRegistrationCapture {
        executionLock.lock()
        try {
            toolPkgRegistrationSession.begin()
            try {
                val executionResult =
                    executeScriptFunction(
                    script = script,
                    functionName = functionName,
                    params = params,
                    timeoutSec = 12L
                )
                return toolPkgRegistrationSession.finish(executionResult)
            } finally {
                toolPkgRegistrationSession.end()
            }
        } finally {
            executionLock.unlock()
        }
    }

    fun executeComposeDslScript(
            script: String,
            runtimeOptions: Map<String, Any?> = emptyMap(),
            envOverrides: Map<String, String> = emptyMap()
    ): Any? {
        return executeScriptFunction(
                script = buildComposeDslRuntimeWrappedScript(script),
                functionName = "__operit_render_compose_dsl",
                params = runtimeOptions,
                envOverrides = envOverrides
        )
    }

    fun executeComposeDslAction(
            actionId: String,
            payload: Any? = null,
            envOverrides: Map<String, String> = emptyMap(),
            onIntermediateResult: ((Any?) -> Unit)? = null
    ): Any? {
        val normalizedActionId = actionId.trim()
        if (normalizedActionId.isBlank()) {
            return "Error: compose action id is required"
        }
        val params = mutableMapOf<String, Any?>("__action_id" to normalizedActionId)
        if (payload != null) {
            params["__action_payload"] = payload
        }
        return executeScriptFunction(
                script = "",
                functionName = "__operit_dispatch_compose_dsl_action",
                params = params,
                envOverrides = envOverrides,
                onIntermediateResult = onIntermediateResult
        )
    }

    private fun toJsLiteral(value: Any?): String {
        if (value == null) {
            return "undefined"
        }
        return when (value) {
            is Number, is Boolean -> value.toString()
            is String -> JSONObject.quote(value)
            else -> {
                try {
                    val wrapped = JSONObject.wrap(value)
                    when (wrapped) {
                        null -> JSONObject.quote(value.toString())
                        JSONObject.NULL -> "null"
                        else -> wrapped.toString()
                    }
                } catch (e: Exception) {
                    JSONObject.quote(value.toString())
                }
            }
        }
    }

    fun dispatchComposeDslActionAsync(
            actionId: String,
            payload: Any? = null,
            envOverrides: Map<String, String> = emptyMap(),
            onIntermediateResult: ((Any?) -> Unit)? = null,
            onComplete: (() -> Unit)? = null,
            onError: ((String) -> Unit)? = null
    ): Boolean {
        val normalizedActionId = actionId.trim()
        if (normalizedActionId.isBlank()) {
            onError?.invoke("compose action id is required")
            onComplete?.invoke()
            return false
        }

        if (onIntermediateResult != null) {
            intermediateResultCallback = onIntermediateResult
        }
        this.envOverrides = envOverrides

        initWebView()
        if (!jsEnvironmentInitialized) {
            initJavaScriptEnvironment()
        }

        val actionToken = UUID.randomUUID().toString()
        if (onComplete != null) {
            composeDslActionCompleteCallbacks[actionToken] = onComplete
        }
        if (onError != null) {
            composeDslActionErrorCallbacks[actionToken] = onError
        }

        val actionIdJson = JSONObject.quote(normalizedActionId)
        val actionTokenJson = JSONObject.quote(actionToken)
        val hasPayload = payload != null
        val payloadLiteral = if (hasPayload) toJsLiteral(payload) else "undefined"
        val asyncDispatchScript =
                """
            (function() {
                var __actionToken = $actionTokenJson;
                try {
                    var __dispatchFn =
                        (typeof window !== 'undefined' && typeof window.__operit_dispatch_compose_dsl_action === 'function')
                            ? window.__operit_dispatch_compose_dsl_action
                            : (typeof __operit_dispatch_compose_dsl_action === 'function'
                                ? __operit_dispatch_compose_dsl_action
                                : null);
                    if (typeof __dispatchFn !== 'function') {
                        NativeInterface.reportComposeDslActionError(__actionToken, 'compose_dsl runtime dispatch function not found');
                        NativeInterface.reportComposeDslActionCompleted(__actionToken);
                        return;
                    }

                    var __request = { __action_id: $actionIdJson };
                    if ($hasPayload) {
                        __request.__action_payload = $payloadLiteral;
                    }

                    Promise.resolve(__dispatchFn(__request))
                        .then(function(__result) {
                            try {
                                NativeInterface.sendIntermediateResult(JSON.stringify(__result));
                            } catch (__ignored) {
                            }
                            NativeInterface.reportComposeDslActionCompleted(__actionToken);
                        })
                        .catch(function(__error) {
                            var __message = (__error && __error.message) ? __error.message : String(__error);
                            NativeInterface.reportComposeDslActionError(__actionToken, __message);
                            NativeInterface.reportComposeDslActionCompleted(__actionToken);
                        });
                } catch (__e) {
                    var __message = (__e && __e.message) ? __e.message : String(__e);
                    NativeInterface.reportComposeDslActionError(__actionToken, __message);
                    NativeInterface.reportComposeDslActionCompleted(__actionToken);
                }
            })();
        """.trimIndent()

        ContextCompat.getMainExecutor(context).execute {
            try {
                webView?.evaluateJavascript(asyncDispatchScript, null)
            } catch (e: Exception) {
                val errorText = "dispatch compose action failed: ${e.message}"
                AppLogger.e(TAG, errorText, e)
                composeDslActionErrorCallbacks.remove(actionToken)?.invoke(errorText)
                composeDslActionCompleteCallbacks.remove(actionToken)?.invoke()
            }
        }

        return true
    }

    fun cancelCurrentExecution(reason: String = "Execution canceled: requested by caller") {
        AppLogger.d(TAG, "Cancel current JS execution: $reason")
        resetState(cancellationMessage = reason)
        if (webView != null) {
            ContextCompat.getMainExecutor(context).execute {
                try {
                    webView?.evaluateJavascript(
                            "window._hasCompleted = true; clearTimeout(window._safetyTimeout);",
                            null
                    )
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error stopping current JS execution: ${e.message}", e)
                }
            }
        }
    }

    /** 重置引擎状态，避免多次调用时的状态干扰 */
    private fun resetState(cancellationMessage: String = "Execution canceled: new execution started") {
        // 只有当之前的回调存在时才需要完成它
        val callback = resultCallback
        if (callback != null && !callback.isDone) {
            try {
                callback.complete(cancellationMessage)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error completing previous callback: ${e.message}", e)
            }
        }
        resultCallback = null
        intermediateResultCallback = null

        // 清理所有待处理的工具调用回调
        toolCallbacks.forEach { (_, future) ->
            if (!future.isDone) {
                future.complete("Operation canceled: engine reset")
            }
        }
        toolCallbacks.clear()
        composeDslActionCompleteCallbacks.clear()
        composeDslActionErrorCallbacks.clear()

        envOverrides = emptyMap()
        toolPkgExecutionContext.clear()

        // 清理Bitmap注册表
        bitmapRegistry.values.forEach { it.recycle() }
        bitmapRegistry.clear()

        // 清理二进制数据注册表
        binaryDataRegistry.clear()
        javaObjectRegistry.clear()

        // 如果WebView已经存在，执行轻量级清理
        if (webView != null) {
            ContextCompat.getMainExecutor(context).execute {
                try {
                    // 使用更简单、更安全的清理代码
                    webView?.evaluateJavascript(
                            """
                        // 清理所有定时器
                        (function() {
                            var highestTimeoutId = setTimeout(";");
                            for (var i = 0 ; i < highestTimeoutId ; i++) {
                                clearTimeout(i);
                                clearInterval(i);
                            }
                        })();
                    """,
                            null
                    )
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error in WebView cleanup: ${e.message}", e)
                }
            }
        }
    }

    /** JavaScript 接口，提供 Native 调用方法 */
    @Keep
    inner class JsToolCallInterface {

        @JavascriptInterface
        fun decompress(data: String, algorithm: String): String {
            return JsNativeInterfaceDelegates.decompress(
                data = data,
                algorithm = algorithm,
                binaryDataRegistry = binaryDataRegistry,
                binaryHandlePrefix = BINARY_HANDLE_PREFIX
            )
        }

        @JavascriptInterface
        fun getEnv(key: String): String? {
            return JsNativeInterfaceDelegates.getEnv(
                context = context,
                key = key,
                envOverrides = envOverrides
            )
        }

        @JavascriptInterface
        fun setEnv(key: String, value: String?) {
            JsNativeInterfaceDelegates.setEnv(context = context, key = key, value = value)
        }

        @JavascriptInterface
        fun setEnvs(valuesJson: String) {
            JsNativeInterfaceDelegates.setEnvs(context = context, valuesJson = valuesJson)
        }

        @JavascriptInterface
        fun isPackageImported(packageName: String): Boolean {
            return JsNativeInterfaceDelegates.isPackageImported(
                    packageManager = packageManager,
                    packageName = packageName
            )
        }

        @JavascriptInterface
        fun importPackage(packageName: String): String {
            return JsNativeInterfaceDelegates.importPackage(
                    packageManager = packageManager,
                    packageName = packageName
            )
        }

        @JavascriptInterface
        fun removePackage(packageName: String): String {
            return JsNativeInterfaceDelegates.removePackage(
                    packageManager = packageManager,
                    packageName = packageName
            )
        }

        @JavascriptInterface
        fun usePackage(packageName: String): String {
            return JsNativeInterfaceDelegates.usePackage(
                    packageManager = packageManager,
                    packageName = packageName
            )
        }

        @JavascriptInterface
        fun listImportedPackagesJson(): String {
            return JsNativeInterfaceDelegates.listImportedPackagesJson(
                    packageManager = packageManager
            )
        }

        @JavascriptInterface
        fun resolveToolName(
                packageName: String,
                subpackageId: String,
                toolName: String,
                preferImported: String
        ): String {
            return JsNativeInterfaceDelegates.resolveToolName(
                    packageManager = packageManager,
                    packageName = packageName,
                    subpackageId = subpackageId,
                    toolName = toolName,
                    preferImported = preferImported
            )
        }

        @JavascriptInterface
        fun readToolPkgResource(
                packageNameOrSubpackageId: String,
                resourceKey: String,
                outputFileName: String
        ): String {
            return JsNativeInterfaceDelegates.readToolPkgResource(
                    packageManager = packageManager,
                    packageNameOrSubpackageId = packageNameOrSubpackageId,
                    resourceKey = resourceKey,
                    outputFileName = outputFileName
            )
        }

        @JavascriptInterface
        fun readToolPkgTextResource(
                packageNameOrSubpackageId: String,
                resourcePath: String
        ): String {
            val temporaryResolverActive = hasTemporaryToolPkgTextResourceResolver()
            resolveTemporaryToolPkgTextResource(
                packageNameOrSubpackageId = packageNameOrSubpackageId,
                resourcePath = resourcePath
            )?.let { resolved -> return resolved }
            if (temporaryResolverActive) {
                // During toolpkg parsing we must not fall back into PackageManager.
                // That fallback can wait on initialization and deadlock JavaBridge thread.
                return ""
            }
            return JsNativeInterfaceDelegates.readToolPkgTextResource(
                    packageManager = packageManager,
                    packageNameOrSubpackageId = packageNameOrSubpackageId,
                    resourcePath = resourcePath
            )
        }

        @JavascriptInterface
        fun measureComposeText(payloadJson: String): String {
            return JsNativeInterfaceDelegates.measureComposeText(
                context = context,
                payloadJson = payloadJson
            )
        }

        @JavascriptInterface
        fun registerToolPkgToolboxUiModule(specJson: String) {
            toolPkgRegistrationSession.appendToolboxUiModule(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgAppLifecycleHook(specJson: String) {
            toolPkgRegistrationSession.appendAppLifecycleHook(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgMessageProcessingPlugin(specJson: String) {
            toolPkgRegistrationSession.appendMessageProcessingPlugin(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgXmlRenderPlugin(specJson: String) {
            toolPkgRegistrationSession.appendXmlRenderPlugin(specJson)
        }

        @JavascriptInterface
        fun registerToolPkgInputMenuTogglePlugin(specJson: String) {
            toolPkgRegistrationSession.appendInputMenuTogglePlugin(specJson)
        }

        @JavascriptInterface
        fun javaClassExists(className: String): Boolean {
            return JsJavaBridgeDelegates.classExists(className = className)
        }

        @JavascriptInterface
        fun javaGetApplicationContext(): String {
            return try {
                val appContext = context.applicationContext
                val handle = UUID.randomUUID().toString()
                javaObjectRegistry[handle] = appContext

                JSONObject()
                    .put("success", true)
                    .put(
                        "data",
                        JSONObject()
                            .put("__javaHandle", handle)
                            .put("__javaClass", appContext.javaClass.name)
                    )
                    .toString()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to expose application context: ${e.message}", e)
                JSONObject()
                    .put("success", false)
                    .put("error", e.message ?: "failed to expose application context")
                    .toString()
            }
        }

        @JavascriptInterface
        fun javaGetCurrentActivity(): String {
            return try {
                val activity =
                    ActivityLifecycleManager.getCurrentActivity()
                        ?: throw IllegalStateException("current activity is null")
                val handle = UUID.randomUUID().toString()
                javaObjectRegistry[handle] = activity

                JSONObject()
                    .put("success", true)
                    .put(
                        "data",
                        JSONObject()
                            .put("__javaHandle", handle)
                            .put("__javaClass", activity.javaClass.name)
                    )
                    .toString()
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to expose current activity: ${e.message}", e)
                JSONObject()
                    .put("success", false)
                    .put("error", e.message ?: "failed to expose current activity")
                    .toString()
            }
        }

        @JavascriptInterface
        fun javaNewInstance(className: String, argsJson: String): String {
            return JsJavaBridgeDelegates.newInstance(
                    className = className,
                    argsJson = argsJson,
                    objectRegistry = javaObjectRegistry,
                    jsCallbackInvoker = { jsObjectId, methodName, callbackArgsJson ->
                        invokeJavaBridgeJsObjectCallbackSync(
                            jsObjectId = jsObjectId,
                            methodName = methodName,
                            argsJson = callbackArgsJson
                        )
                    },
                    bridgeClassLoader = getJavaBridgeClassLoader()
            )
        }

        @JavascriptInterface
        fun javaCallStatic(className: String, methodName: String, argsJson: String): String {
            return JsJavaBridgeDelegates.callStatic(
                    className = className,
                    methodName = methodName,
                    argsJson = argsJson,
                    objectRegistry = javaObjectRegistry,
                    jsCallbackInvoker = { jsObjectId, callbackMethod, callbackArgsJson ->
                        invokeJavaBridgeJsObjectCallbackSync(
                            jsObjectId = jsObjectId,
                            methodName = callbackMethod,
                            argsJson = callbackArgsJson
                        )
                    },
                    bridgeClassLoader = getJavaBridgeClassLoader()
            )
        }

        @JavascriptInterface
        fun javaCallInstance(instanceHandle: String, methodName: String, argsJson: String): String {
            return JsJavaBridgeDelegates.callInstance(
                    instanceHandle = instanceHandle,
                    methodName = methodName,
                    argsJson = argsJson,
                    objectRegistry = javaObjectRegistry,
                    jsCallbackInvoker = { jsObjectId, callbackMethod, callbackArgsJson ->
                        invokeJavaBridgeJsObjectCallbackSync(
                            jsObjectId = jsObjectId,
                            methodName = callbackMethod,
                            argsJson = callbackArgsJson
                        )
                    },
                    bridgeClassLoader = getJavaBridgeClassLoader()
            )
        }

        @JavascriptInterface
        fun javaCallStaticSuspend(
                className: String,
                methodName: String,
                argsJson: String,
                callbackId: String
        ) {
            val normalizedCallback = callbackId.trim()
            if (normalizedCallback.isEmpty()) {
                return
            }
            Thread {
                JsJavaBridgeDelegates.callStaticSuspend(
                    className = className,
                    methodName = methodName,
                    argsJson = argsJson,
                    objectRegistry = javaObjectRegistry,
                    callback = { resultJson ->
                        val (error, data) = splitBridgeResult(resultJson)
                        val argsJsonPayload =
                            JSONArray()
                                .put(error)
                                .put(data)
                                .toString()
                        invokeJavaBridgeJsObjectCallbackSync(
                            jsObjectId = normalizedCallback,
                            methodName = "",
                            argsJson = argsJsonPayload
                        )
                    },
                    jsCallbackInvoker = { jsObjectId, callbackMethod, callbackArgsJson ->
                        invokeJavaBridgeJsObjectCallbackSync(
                            jsObjectId = jsObjectId,
                            methodName = callbackMethod,
                            argsJson = callbackArgsJson
                        )
                    },
                    bridgeClassLoader = getJavaBridgeClassLoader()
                )
            }.start()
        }

        @JavascriptInterface
        fun javaCallInstanceSuspend(
                instanceHandle: String,
                methodName: String,
                argsJson: String,
                callbackId: String
        ) {
            val normalizedCallback = callbackId.trim()
            if (normalizedCallback.isEmpty()) {
                return
            }
            Thread {
                JsJavaBridgeDelegates.callInstanceSuspend(
                    instanceHandle = instanceHandle,
                    methodName = methodName,
                    argsJson = argsJson,
                    objectRegistry = javaObjectRegistry,
                    callback = { resultJson ->
                        val (error, data) = splitBridgeResult(resultJson)
                        val argsJsonPayload =
                            JSONArray()
                                .put(error)
                                .put(data)
                                .toString()
                        invokeJavaBridgeJsObjectCallbackSync(
                            jsObjectId = normalizedCallback,
                            methodName = "",
                            argsJson = argsJsonPayload
                        )
                    },
                    jsCallbackInvoker = { jsObjectId, callbackMethod, callbackArgsJson ->
                        invokeJavaBridgeJsObjectCallbackSync(
                            jsObjectId = jsObjectId,
                            methodName = callbackMethod,
                            argsJson = callbackArgsJson
                        )
                    },
                    bridgeClassLoader = getJavaBridgeClassLoader()
                )
            }.start()
        }

        @JavascriptInterface
        fun javaGetStaticField(className: String, fieldName: String): String {
            return JsJavaBridgeDelegates.getStaticField(
                    className = className,
                    fieldName = fieldName,
                    objectRegistry = javaObjectRegistry
            )
        }

        @JavascriptInterface
        fun javaSetStaticField(className: String, fieldName: String, valueJson: String): String {
            return JsJavaBridgeDelegates.setStaticField(
                    className = className,
                    fieldName = fieldName,
                    valueJson = valueJson,
                    objectRegistry = javaObjectRegistry,
                    jsCallbackInvoker = { jsObjectId, callbackMethod, callbackArgsJson ->
                        invokeJavaBridgeJsObjectCallbackSync(
                            jsObjectId = jsObjectId,
                            methodName = callbackMethod,
                            argsJson = callbackArgsJson
                        )
                    },
                    bridgeClassLoader = getJavaBridgeClassLoader()
            )
        }

        @JavascriptInterface
        fun javaGetInstanceField(instanceHandle: String, fieldName: String): String {
            return JsJavaBridgeDelegates.getInstanceField(
                    instanceHandle = instanceHandle,
                    fieldName = fieldName,
                    objectRegistry = javaObjectRegistry
            )
        }

        @JavascriptInterface
        fun javaSetInstanceField(instanceHandle: String, fieldName: String, valueJson: String): String {
            return JsJavaBridgeDelegates.setInstanceField(
                    instanceHandle = instanceHandle,
                    fieldName = fieldName,
                    valueJson = valueJson,
                    objectRegistry = javaObjectRegistry,
                    jsCallbackInvoker = { jsObjectId, callbackMethod, callbackArgsJson ->
                        invokeJavaBridgeJsObjectCallbackSync(
                            jsObjectId = jsObjectId,
                            methodName = callbackMethod,
                            argsJson = callbackArgsJson
                        )
                    },
                    bridgeClassLoader = getJavaBridgeClassLoader()
            )
        }

        @JavascriptInterface
        fun javaReleaseInstance(instanceHandle: String): String {
            return JsJavaBridgeDelegates.releaseInstance(
                    instanceHandle = instanceHandle,
                    objectRegistry = javaObjectRegistry
            )
        }

        @JavascriptInterface
        fun javaReleaseAllInstances(): String {
            return JsJavaBridgeDelegates.releaseAllInstances(
                    objectRegistry = javaObjectRegistry
            )
        }

        @JavascriptInterface
        fun registerImageFromBase64(base64: String, mimeType: String): String {
            return try {
                val finalMime = if (mimeType.isNotBlank()) mimeType else "image/png"
                val id = ImagePoolManager.addImageFromBase64(base64, finalMime)
                if (id != "error") {
                    "<link type=\"image\" id=\"$id\"></link>"
                } else {
                    "[image registration failed]"
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "registerImageFromBase64 failed: ${e.message}", e)
                "[image registration failed: ${e.message}]"
            }
        }

        @JavascriptInterface
        fun registerImageFromPath(path: String): String {
            return try {
                val id = ImagePoolManager.addImage(path)
                if (id != "error") {
                    "<link type=\"image\" id=\"$id\"></link>"
                } else {
                    "[image registration failed]"
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "registerImageFromPath failed: ${e.message}", e)
                "[image registration failed: ${e.message}]"
            }
        }

        @JavascriptInterface
        fun image_processing(callbackId: String, operation: String, argsJson: String) {
            JsNativeInterfaceDelegates.imageProcessing(
                    callbackId = callbackId,
                    operation = operation,
                    argsJson = argsJson,
                    binaryDataRegistry = binaryDataRegistry,
                    bitmapRegistry = bitmapRegistry,
                    binaryHandlePrefix = BINARY_HANDLE_PREFIX
            ) { callback, result, isError ->
                sendToolResult(callback, result, isError)
            }
        }

        @JavascriptInterface
        fun crypto(algorithm: String, operation: String, argsJson: String): String {
            return JsNativeInterfaceDelegates.crypto(
                    algorithm = algorithm,
                    operation = operation,
                    argsJson = argsJson
            )
        }

        @JavascriptInterface
        fun sendIntermediateResult(result: String) {
            try {
                ContextCompat.getMainExecutor(context).execute {
                    intermediateResultCallback?.invoke(result)
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error processing intermediate result: ${e.message}", e)
            }
        }

        @JavascriptInterface
        fun reportComposeDslActionError(actionToken: String, error: String) {
            try {
                AppLogger.e(
                        TAG,
                        "compose_dsl async action error: token=$actionToken, error=$error"
                )
                val callback = composeDslActionErrorCallbacks.remove(actionToken)
                if (callback != null) {
                    ContextCompat.getMainExecutor(context).execute {
                        callback.invoke(error)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error reporting compose_dsl action error: ${e.message}", e)
            }
        }

        @JavascriptInterface
        fun reportComposeDslActionCompleted(actionToken: String) {
            try {
                val callback = composeDslActionCompleteCallbacks.remove(actionToken)
                composeDslActionErrorCallbacks.remove(actionToken)
                if (callback != null) {
                    ContextCompat.getMainExecutor(context).execute {
                        callback.invoke()
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error reporting compose_dsl action completion: ${e.message}", e)
            }
        }

        /** 同步工具调用（旧版本，保留兼容性） */
        @JavascriptInterface
        fun callTool(toolType: String, toolName: String, paramsJson: String): String {
            return JsNativeInterfaceDelegates.callToolSync(
                toolHandler = toolHandler,
                toolType = toolType,
                toolName = toolName,
                paramsJson = paramsJson,
                binaryDataRegistry = binaryDataRegistry,
                binaryHandlePrefix = BINARY_HANDLE_PREFIX,
                binaryDataThreshold = BINARY_DATA_THRESHOLD
            )
        }

        /** 异步工具调用（新版本，使用Promise） */
        @JavascriptInterface
        fun callToolAsync(
                callbackId: String,
                toolType: String,
                toolName: String,
                paramsJson: String
        ) {
            JsNativeInterfaceDelegates.callToolAsync(
                toolHandler = toolHandler,
                callbackId = callbackId,
                toolType = toolType,
                toolName = toolName,
                paramsJson = paramsJson,
                binaryDataRegistry = binaryDataRegistry,
                binaryHandlePrefix = BINARY_HANDLE_PREFIX,
                binaryDataThreshold = BINARY_DATA_THRESHOLD,
                sendToolResult = { callback, result, isError ->
                    sendToolResult(callback, result, isError)
                }
            )
        }

        /** 向JavaScript发送工具调用结果 */
        private fun sendToolResult(callbackId: String, result: String, isError: Boolean) {
            ContextCompat.getMainExecutor(context).execute {
                try {
                    val jsCode =
                        JsNativeInterfaceDelegates.buildToolResultCallbackScript(
                            callbackId = callbackId,
                            result = result,
                            isError = isError
                        )
                    webView?.evaluateJavascript(jsCode, null)
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error sending tool result to JavaScript: ${e.message}", e)
                }
            }
        }

        @JavascriptInterface
        fun setResult(result: String) {
            try {
                val callback =
                    resolveActiveResultCallback(
                        payloadLength = result.length,
                        onNullMessage = "Result callback is null when trying to complete",
                        onDoneMessage = "Result callback is already completed when trying to set result"
                    )
                        ?: return

                completeResultCallback(
                    callback = callback,
                    value = result,
                    failureMessage = "Error completing result callback"
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting result: ${e.message}", e)
                resultCallback?.completeExceptionally(e)
            }
        }

        @JavascriptInterface
        fun setError(error: String) {
            try {
                val callback =
                    resolveActiveResultCallback(
                        payloadLength = error.length,
                        onNullMessage = "Result callback is null when trying to complete with error",
                        onDoneMessage = "Result callback is already completed when trying to set error"
                    )
                        ?: return

                val logMessage = extractErrorLogMessage(error)
                val enrichedLogMessage = withToolpkgCodeContext(logMessage)
                AppLogger.e(TOOLPKG_TAG, withToolpkgPluginTag("JS ERROR: $enrichedLogMessage"))

                completeResultCallback(
                    callback = callback,
                    value = "Error: ${withToolpkgCodeContext(error)}",
                    failureMessage = "Error completing error callback"
                )
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error setting error result: ${e.message}", e)
                resultCallback?.completeExceptionally(e)
            }
        }

        private fun resolveActiveResultCallback(
            payloadLength: Int,
            onNullMessage: String,
            onDoneMessage: String
        ): CompletableFuture<Any?>? {
            val callback = resultCallback
            AppLogger.d(
                TAG,
                "Bridge callback from JavaScript: length=$payloadLength, callback=${callback != null}, isDone=${callback?.isDone}"
            )
            if (callback == null) {
                AppLogger.e(TAG, onNullMessage)
                return null
            }
            if (callback.isDone) {
                AppLogger.w(TAG, onDoneMessage)
                return null
            }
            return callback
        }

        private fun completeResultCallback(
            callback: CompletableFuture<Any?>,
            value: String,
            failureMessage: String
        ) {
            try {
                if (!callback.isDone) {
                    callback.complete(value)
                } else {
                    AppLogger.w(TAG, "Callback became complete between check and execution")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "$failureMessage: ${e.message}", e)
                if (!callback.isDone) {
                    callback.completeExceptionally(e)
                }
            }
        }

        private fun extractErrorLogMessage(error: String): String {
            return try {
                if (error.startsWith("{") && error.endsWith("}")) {
                    val errorJson = JSONObject(error)
                    if (errorJson.has("formatted")) {
                        return errorJson.getString("formatted")
                    }
                    if (errorJson.has("error") && errorJson.has("message")) {
                        val errorType = errorJson.getString("error")
                        val errorMsg = errorJson.getString("message")
                        var message = "$errorType: $errorMsg"
                        if (errorJson.has("details")) {
                            val details = errorJson.getJSONObject("details")
                            if (details.has("fileName") && details.has("lineNumber")) {
                                message +=
                                    "\nAt ${details.getString("fileName")}:${details.getString("lineNumber")}"
                            }
                            if (details.has("stack")) {
                                message += "\nStack: ${details.getString("stack")}"
                            }
                        }
                        return message
                    }
                }
                error
            } catch (e: Exception) {
                AppLogger.d(TAG, "Error parsing error message as JSON: ${e.message}")
                error
            }
        }

        @JavascriptInterface
        fun logInfo(message: String) {
            AppLogger.i(TOOLPKG_TAG, withToolpkgPluginTag(message))
        }

        @JavascriptInterface
        fun logError(message: String) {
            AppLogger.e(TOOLPKG_TAG, withToolpkgPluginTag(message))
        }

        @JavascriptInterface
        fun logDebug(message: String, data: String) {
            AppLogger.d(TOOLPKG_TAG, withToolpkgPluginTag("$message | $data"))
        }

        @JavascriptInterface
        fun reportError(
                errorType: String,
                errorMessage: String,
                errorLine: Int,
                errorStack: String
        ) {
            AppLogger.e(
                    TOOLPKG_TAG,
                    withToolpkgPluginTag(
                        "DETAILED JS ERROR: \nType: $errorType\nMessage: $errorMessage\nLine: $errorLine\nStack: $errorStack"
                    )
            )
        }
    }

    /** 销毁引擎资源 */
    fun destroy() {
        try {
            // 确保任何挂起的回调被完成
            resultCallback?.complete("Engine destroyed")
            resultCallback = null
            intermediateResultCallback = null

            // 清理所有待处理的工具调用回调
            toolCallbacks.forEach { (_, future) ->
                if (!future.isDone) {
                    future.complete("Engine destroyed")
                }
            }
            toolCallbacks.clear()
            composeDslActionCompleteCallbacks.clear()
            composeDslActionErrorCallbacks.clear()

            // 清理Bitmap注册表
            bitmapRegistry.values.forEach { it.recycle() }
            bitmapRegistry.clear()

            // 清理二进制数据注册表
            binaryDataRegistry.clear()
            javaObjectRegistry.clear()

            // 在主线程中销毁 WebView
            ContextCompat.getMainExecutor(context).execute {
                try {
                    webView?.apply {
                        removeJavascriptInterface("NativeInterface")
                        loadUrl("about:blank")
                        clearHistory()
                        clearCache(true)
                        destroy()
                    }
                    webView = null
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error destroying WebView: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error during JsEngine destruction: ${e.message}", e)
        }
    }

    /** 处理引擎异常 */
    private fun handleException(e: Exception): String {
        AppLogger.e(TAG, "JsEngine exception: ${e.message}", e)

        // 尝试重置当前状态
        try {
            resetState()
        } catch (resetEx: Exception) {
            AppLogger.e(TAG, "Failed to reset state after exception: ${resetEx.message}", resetEx)
        }

        return "Error: ${e.message}"
    }

    /** 诊断引擎状态 用于调试目的，记录当前状态信息 */
    fun diagnose() {
        try {
            AppLogger.d(TAG, "=== JsEngine Diagnostics ===")
            AppLogger.d(TAG, "WebView initialized: ${webView != null}")
            AppLogger.d(TAG, "Result callback: ${resultCallback?.isDone ?: "null"}")
            AppLogger.d(TAG, "Tool callbacks pending: ${toolCallbacks.size}")

            // 检查WebView状态
            if (webView != null) {
                ContextCompat.getMainExecutor(context).execute {
                    webView?.evaluateJavascript(
                            """
                        (function() {
                            var result = {
                                memory: (window.performance && window.performance.memory) 
                                    ? {
                                        totalJSHeapSize: window.performance.memory.totalJSHeapSize,
                                        usedJSHeapSize: window.performance.memory.usedJSHeapSize,
                                        jsHeapSizeLimit: window.performance.memory.jsHeapSizeLimit
                                      } 
                                    : "Not available",
                                timers: "Unable to count"
                            };
                            
                            // 尝试估计定时器数量
                            try {
                                var count = 0;
                                var id = setTimeout(function(){}, 0);
                                clearTimeout(id);
                                result.timers = id;
                            } catch(e) {}
                            
                            return JSON.stringify(result);
                        })();
                    """
                    ) { diagResult -> AppLogger.d(TAG, "WebView diagnostics: $diagResult") }
                }
            }

            AppLogger.d(TAG, "=========================")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error during diagnostics: ${e.message}", e)
        }
    }
}

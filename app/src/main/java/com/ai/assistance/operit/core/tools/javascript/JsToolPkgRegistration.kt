package com.ai.assistance.operit.core.tools.javascript

import org.json.JSONObject
import org.json.JSONTokener

data class ToolPkgMainRegistrationCapture(
    val toolboxUiModules: List<String>,
    val appLifecycleHooks: List<String>,
    val messageProcessingPlugins: List<String>,
    val xmlRenderPlugins: List<String>,
    val inputMenuTogglePlugins: List<String>
)

private data class MutableToolPkgMainRegistrationCapture(
    val toolboxUiModules: MutableList<String> = mutableListOf(),
    val appLifecycleHooks: MutableList<String> = mutableListOf(),
    val messageProcessingPlugins: MutableList<String> = mutableListOf(),
    val xmlRenderPlugins: MutableList<String> = mutableListOf(),
    val inputMenuTogglePlugins: MutableList<String> = mutableListOf()
)

internal class JsToolPkgRegistrationSession {
    private val lock = Any()
    private var capture: MutableToolPkgMainRegistrationCapture? = null

    fun begin() {
        synchronized(lock) {
            capture = MutableToolPkgMainRegistrationCapture()
        }
    }

    fun appendToolboxUiModule(specJson: String) {
        val normalized = normalizeRegistrationSpec(specJson)
        withActiveCapture { current ->
            current.toolboxUiModules.add(normalized)
        }
    }

    fun appendAppLifecycleHook(specJson: String) {
        val normalized = normalizeRegistrationSpec(specJson)
        withActiveCapture { current ->
            current.appLifecycleHooks.add(normalized)
        }
    }

    fun appendMessageProcessingPlugin(specJson: String) {
        val normalized = normalizeRegistrationSpec(specJson)
        withActiveCapture { current ->
            current.messageProcessingPlugins.add(normalized)
        }
    }

    fun appendXmlRenderPlugin(specJson: String) {
        val normalized = normalizeRegistrationSpec(specJson)
        withActiveCapture { current ->
            current.xmlRenderPlugins.add(normalized)
        }
    }

    fun appendInputMenuTogglePlugin(specJson: String) {
        val normalized = normalizeRegistrationSpec(specJson)
        withActiveCapture { current ->
            current.inputMenuTogglePlugins.add(normalized)
        }
    }

    fun finish(executionResult: Any?): ToolPkgMainRegistrationCapture {
        if (executionResult is String) {
            val normalized = executionResult.trim()
            if (normalized.startsWith("Error:", ignoreCase = true)) {
                val message = normalized.substringAfter(":", normalized).trim()
                throw IllegalStateException(message.ifBlank { "toolpkg main registration failed" })
            }
        }
        synchronized(lock) {
            val current = capture ?: MutableToolPkgMainRegistrationCapture()
            return ToolPkgMainRegistrationCapture(
                toolboxUiModules = current.toolboxUiModules.toList(),
                appLifecycleHooks = current.appLifecycleHooks.toList(),
                messageProcessingPlugins = current.messageProcessingPlugins.toList(),
                xmlRenderPlugins = current.xmlRenderPlugins.toList(),
                inputMenuTogglePlugins = current.inputMenuTogglePlugins.toList()
            )
        }
    }

    fun end() {
        synchronized(lock) {
            capture = null
        }
    }

    private fun withActiveCapture(action: (MutableToolPkgMainRegistrationCapture) -> Unit) {
        synchronized(lock) {
            val current =
                capture ?: throw IllegalStateException("toolpkg registration session is not active")
            action(current)
        }
    }

    private fun normalizeRegistrationSpec(specJson: String): String {
        val trimmed = specJson.trim()
        if (trimmed.isEmpty()) {
            throw IllegalArgumentException("toolpkg registration payload is empty")
        }
        val parsed = JSONTokener(trimmed).nextValue()
        if (parsed !is JSONObject) {
            throw IllegalArgumentException("toolpkg registration payload must be a JSON object")
        }
        return parsed.toString()
    }
}

internal fun buildToolPkgRegistrationBridgeScript(): String {
    return """
        var __operitToolPkgHookCounter = 0;

        function registerToolPkgToolboxUiModule(definition) {
            if (!definition || typeof definition !== 'object' || Array.isArray(definition)) {
                throw new Error("registerToolPkgToolboxUiModule expects an object");
            }
            if (typeof NativeInterface === 'undefined' || typeof NativeInterface.registerToolPkgToolboxUiModule !== 'function') {
                throw new Error("NativeInterface.registerToolPkgToolboxUiModule is unavailable");
            }
            var normalized = {};
            var keys = Object.keys(definition);
            for (var i = 0; i < keys.length; i += 1) {
                var key = keys[i];
                if (key === 'screen') {
                    continue;
                }
                normalized[key] = definition[key];
            }

            var rawScreen = definition.screen;
            var resolvedScreen = '';
            if (typeof rawScreen === 'string') {
                resolvedScreen = rawScreen.trim().replace(/\\/g, '/');
            } else if (typeof rawScreen === 'function') {
                var marker = rawScreen.__operit_toolpkg_module_path;
                if (typeof marker === 'string') {
                    resolvedScreen = marker.trim().replace(/\\/g, '/');
                }
            } else if (rawScreen && typeof rawScreen === 'object' && typeof rawScreen.default === 'function') {
                var defaultMarker = rawScreen.default.__operit_toolpkg_module_path;
                if (typeof defaultMarker === 'string') {
                    resolvedScreen = defaultMarker.trim().replace(/\\/g, '/');
                }
            }

            if (!resolvedScreen) {
                throw new Error("registerToolPkgToolboxUiModule requires a serializable screen reference");
            }
            normalized.screen = resolvedScreen;
            NativeInterface.registerToolPkgToolboxUiModule(JSON.stringify(normalized));
        }

        function registerToolPkgAppLifecycleHook(definition) {
            if (!definition || typeof definition !== 'object' || Array.isArray(definition)) {
                throw new Error("registerToolPkgAppLifecycleHook expects an object");
            }
            if (typeof NativeInterface === 'undefined' || typeof NativeInterface.registerToolPkgAppLifecycleHook !== 'function') {
                throw new Error("NativeInterface.registerToolPkgAppLifecycleHook is unavailable");
            }
            var normalized = {};
            var keys = Object.keys(definition);
            for (var i = 0; i < keys.length; i += 1) {
                var key = keys[i];
                if (key === 'function') {
                    continue;
                }
                normalized[key] = definition[key];
            }

            var rawFunction = definition.function;
            var resolvedFunction = '';
            var functionSource = '';
            if (typeof rawFunction === 'function') {
                var exportsRef = window.__operit_current_module_exports;
                if (exportsRef && typeof exportsRef === 'object') {
                    var exportKeys = Object.keys(exportsRef);
                    for (var i = 0; i < exportKeys.length; i += 1) {
                        var exportKey = exportKeys[i];
                        if (exportsRef[exportKey] === rawFunction) {
                            resolvedFunction = exportKey;
                            break;
                        }
                    }
                }

                if (!resolvedFunction) {
                    __operitToolPkgHookCounter += 1;
                    var hookIdPart = String(definition.id || 'hook').replace(/[^a-zA-Z0-9_$]/g, '_');
                    if (!hookIdPart) {
                        hookIdPart = 'hook';
                    }
                    resolvedFunction = "__operit_inline_hook_" + hookIdPart + "_" + __operitToolPkgHookCounter;
                    functionSource = String(rawFunction);
                }
            }

            if (!resolvedFunction) {
                throw new Error("registerToolPkgAppLifecycleHook requires a function reference");
            }
            normalized.function = resolvedFunction;
            if (functionSource) {
                normalized.function_source = functionSource;
            }
            NativeInterface.registerToolPkgAppLifecycleHook(JSON.stringify(normalized));
        }

        function __operitResolveFunctionField(definition, fieldName, errorMessagePrefix) {
            var normalized = {};
            var keys = Object.keys(definition);
            for (var i = 0; i < keys.length; i += 1) {
                var key = keys[i];
                if (key === fieldName) {
                    continue;
                }
                normalized[key] = definition[key];
            }

            var rawFunction = definition[fieldName];
            var resolvedFunction = '';
            var functionSource = '';
            if (typeof rawFunction === 'function') {
                var exportsRef = window.__operit_current_module_exports;
                if (exportsRef && typeof exportsRef === 'object') {
                    var exportKeys = Object.keys(exportsRef);
                    for (var i = 0; i < exportKeys.length; i += 1) {
                        var exportKey = exportKeys[i];
                        if (exportsRef[exportKey] === rawFunction) {
                            resolvedFunction = exportKey;
                            break;
                        }
                    }
                }

                if (!resolvedFunction) {
                    __operitToolPkgHookCounter += 1;
                    var hookIdPart = String(definition.id || 'hook').replace(/[^a-zA-Z0-9_$]/g, '_');
                    if (!hookIdPart) {
                        hookIdPart = 'hook';
                    }
                    resolvedFunction = "__operit_inline_hook_" + hookIdPart + "_" + __operitToolPkgHookCounter;
                    functionSource = String(rawFunction);
                }
            }

            if (!resolvedFunction) {
                throw new Error(errorMessagePrefix + " requires a function reference");
            }
            normalized[fieldName] = resolvedFunction;
            if (functionSource) {
                normalized.function_source = functionSource;
            }
            return normalized;
        }

        function registerToolPkgMessageProcessingPlugin(definition) {
            if (!definition || typeof definition !== 'object' || Array.isArray(definition)) {
                throw new Error("registerToolPkgMessageProcessingPlugin expects an object");
            }
            if (typeof NativeInterface === 'undefined' || typeof NativeInterface.registerToolPkgMessageProcessingPlugin !== 'function') {
                throw new Error("NativeInterface.registerToolPkgMessageProcessingPlugin is unavailable");
            }
            var normalized = __operitResolveFunctionField(
                definition,
                "function",
                "registerToolPkgMessageProcessingPlugin"
            );
            NativeInterface.registerToolPkgMessageProcessingPlugin(JSON.stringify(normalized));
        }

        function registerToolPkgXmlRenderPlugin(definition) {
            if (!definition || typeof definition !== 'object' || Array.isArray(definition)) {
                throw new Error("registerToolPkgXmlRenderPlugin expects an object");
            }
            if (typeof NativeInterface === 'undefined' || typeof NativeInterface.registerToolPkgXmlRenderPlugin !== 'function') {
                throw new Error("NativeInterface.registerToolPkgXmlRenderPlugin is unavailable");
            }
            var normalized = __operitResolveFunctionField(
                definition,
                "function",
                "registerToolPkgXmlRenderPlugin"
            );
            NativeInterface.registerToolPkgXmlRenderPlugin(JSON.stringify(normalized));
        }

        function registerToolPkgInputMenuTogglePlugin(definition) {
            if (!definition || typeof definition !== 'object' || Array.isArray(definition)) {
                throw new Error("registerToolPkgInputMenuTogglePlugin expects an object");
            }
            if (typeof NativeInterface === 'undefined' || typeof NativeInterface.registerToolPkgInputMenuTogglePlugin !== 'function') {
                throw new Error("NativeInterface.registerToolPkgInputMenuTogglePlugin is unavailable");
            }
            var normalized = __operitResolveFunctionField(
                definition,
                "function",
                "registerToolPkgInputMenuTogglePlugin"
            );
            NativeInterface.registerToolPkgInputMenuTogglePlugin(JSON.stringify(normalized));
        }

        function __operitInstallGlobal(name, value) {
            var key = String(name || '').trim();
            if (!key || value === undefined) {
                return;
            }
            try {
                globalThis[key] = value;
            } catch (_globalError) {
            }
            try {
                window[key] = value;
            } catch (_windowError) {
            }
        }

        var __operitToolPkgApi = {
            registerToolboxUiModule: registerToolPkgToolboxUiModule,
            registerAppLifecycleHook: registerToolPkgAppLifecycleHook,
            registerMessageProcessingPlugin: registerToolPkgMessageProcessingPlugin,
            registerXmlRenderPlugin: registerToolPkgXmlRenderPlugin,
            registerInputMenuTogglePlugin: registerToolPkgInputMenuTogglePlugin
        };
        __operitInstallGlobal("ToolPkg", __operitToolPkgApi);
    """.trimIndent()
}

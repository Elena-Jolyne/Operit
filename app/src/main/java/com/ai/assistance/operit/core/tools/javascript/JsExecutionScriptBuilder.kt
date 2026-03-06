package com.ai.assistance.operit.core.tools.javascript

internal fun buildExecutionScript(
    paramsJson: String,
    scriptJson: String,
    functionNameJson: String,
    timeoutSec: Long,
    preTimeoutSeconds: Long
): String {
    val safeTimeoutSec = if (timeoutSec <= 0L) 1L else timeoutSec
    val safePreTimeoutSec = if (preTimeoutSeconds <= 0L) 1L else preTimeoutSeconds
    val preTimeoutMs = safePreTimeoutSec * 1000L

    return """
        (function() {
            var params = $paramsJson;
            var targetFunctionName = $functionNameJson;

            function readStringParam(name) {
                try {
                    if (!params || params[name] === undefined || params[name] === null) {
                        return '';
                    }
                    return String(params[name]).trim();
                } catch (_e) {
                    return '';
                }
            }

            function setStage(value) {
                try {
                    window.__operit_last_exec_stage = String(value || '');
                } catch (_e) {
                }
            }

            function clearExecutionTimeouts() {
                try {
                    if (window._safetyTimeout) {
                        clearTimeout(window._safetyTimeout);
                    }
                    if (window._safetyTimeoutFinal) {
                        clearTimeout(window._safetyTimeoutFinal);
                    }
                } catch (_e) {
                }
            }

            function emitResult(payload) {
                if (window._hasCompleted) {
                    return;
                }
                window._hasCompleted = true;
                clearExecutionTimeouts();
                NativeInterface.setResult(payload);
            }

            function emitError(message) {
                if (window._hasCompleted) {
                    return;
                }
                window._hasCompleted = true;
                clearExecutionTimeouts();
                NativeInterface.setError(String(message || 'Unknown error'));
            }

            function normalizePath(rawPath) {
                var parts = String(rawPath || '').replace(/\\/g, '/').split('/');
                var stack = [];
                for (var i = 0; i < parts.length; i += 1) {
                    var part = parts[i];
                    if (!part || part === '.') {
                        continue;
                    }
                    if (part === '..') {
                        if (stack.length > 0) {
                            stack.pop();
                        }
                        continue;
                    }
                    stack.push(part);
                }
                return stack.join('/');
            }

            function dirname(pathValue) {
                var normalized = normalizePath(pathValue);
                if (!normalized) {
                    return '';
                }
                var idx = normalized.lastIndexOf('/');
                return idx < 0 ? '' : normalized.substring(0, idx);
            }

            function resolveModulePath(request, fromPath) {
                var normalizedRequest = String(request || '').replace(/\\/g, '/').trim();
                if (!normalizedRequest) {
                    return '';
                }
                if (!(normalizedRequest.startsWith('.') || normalizedRequest.startsWith('/'))) {
                    return normalizedRequest;
                }
                if (normalizedRequest.startsWith('/')) {
                    return normalizePath(normalizedRequest);
                }
                var baseDir = dirname(fromPath || '');
                return normalizePath(baseDir ? (baseDir + '/' + normalizedRequest) : normalizedRequest);
            }

            function buildCandidatePaths(modulePath) {
                var normalized = normalizePath(modulePath);
                if (!normalized) {
                    return [];
                }
                var candidates = [normalized];
                var hasExt = /\.[a-z0-9]+$/i.test(normalized);
                if (!hasExt) {
                    candidates.push(normalized + '.js');
                    candidates.push(normalized + '.json');
                    candidates.push(normalized + '/index.js');
                    candidates.push(normalized + '/index.json');
                }
                return candidates;
            }

            function readToolPkgModule(packageTarget, modulePath) {
                if (!packageTarget) {
                    return null;
                }
                if (
                    typeof NativeInterface === 'undefined' ||
                    !NativeInterface ||
                    typeof NativeInterface.readToolPkgTextResource !== 'function'
                ) {
                    return null;
                }
                var candidates = buildCandidatePaths(modulePath);
                for (var i = 0; i < candidates.length; i += 1) {
                    var candidatePath = candidates[i];
                    var moduleText = NativeInterface.readToolPkgTextResource(packageTarget, candidatePath);
                    if (typeof moduleText === 'string' && moduleText.length > 0) {
                        return {
                            path: candidatePath,
                            text: moduleText
                        };
                    }
                }
                return null;
            }

            function createRegistrationScreenPlaceholder(modulePath) {
                function ScreenPlaceholder() {
                    return null;
                }
                try {
                    ScreenPlaceholder.__operit_toolpkg_module_path = String(modulePath || '').trim();
                } catch (_e) {
                }
                return ScreenPlaceholder;
            }

            function createFactory(scriptText) {
                return new Function('module', 'exports', 'require', scriptText);
            }

            try {
                setStage('bootstrap');
                window._hasCompleted = false;
                clearExecutionTimeouts();

                window.__operit_last_exec_function = targetFunctionName;
                window.__operit_last_module_path = '';
                window.__operit_last_require_request = '';
                window.__operit_last_require_from = '';
                window.__operit_last_require_resolved = '';

                var pluginId = readStringParam('__operit_plugin_id') || readStringParam('pluginId') || readStringParam('hookId');
                if (pluginId) {
                    window.__operit_active_plugin_id = pluginId;
                } else {
                    try {
                        delete window.__operit_active_plugin_id;
                    } catch (_deleteError) {
                    }
                }

                window.__operit_package_state = readStringParam('__operit_package_state') || undefined;
                window.__operit_package_lang =
                    readStringParam('__operit_package_lang') ||
                    (window.__operit_package_lang ? String(window.__operit_package_lang) : undefined);
                window.__operit_package_caller_name = readStringParam('__operit_package_caller_name') || undefined;
                window.__operit_package_chat_id = readStringParam('__operit_package_chat_id') || undefined;
                window.__operit_package_caller_card_id = readStringParam('__operit_package_caller_card_id') || undefined;

                window._safetyTimeout = setTimeout(function() {
                    if (window._hasCompleted) {
                        return;
                    }
                    window._safetyTimeoutFinal = setTimeout(function() {
                        emitError("Script execution timed out after $safeTimeoutSec seconds");
                    }, 5000);
                }, $preTimeoutMs);

                var registrationMode = readStringParam('__operit_registration_mode').toLowerCase() === 'true';
                var packageTarget = readStringParam('__operit_ui_package_name') || readStringParam('toolPkgId');
                var screenPath = normalizePath(
                    readStringParam('__operit_script_screen') ||
                    (params && params.moduleSpec && params.moduleSpec.screen ? String(params.moduleSpec.screen) : '')
                );

                var persistentModuleKey = readStringParam('__operit_persistent_module_key');
                if (!window.__operitPersistentModuleCache || typeof window.__operitPersistentModuleCache !== 'object') {
                    window.__operitPersistentModuleCache = {};
                }

                var moduleCache = {};

                function executeModule(modulePath, moduleText, requireInternal) {
                    if (moduleCache[modulePath]) {
                        return moduleCache[modulePath].exports;
                    }

                    setStage('execute_required_module');
                    window.__operit_last_module_path = String(modulePath || '');

                    var localModule = { exports: {} };
                    moduleCache[modulePath] = localModule;

                    if (/\.json$/i.test(modulePath)) {
                        localModule.exports = JSON.parse(moduleText);
                        return localModule.exports;
                    }

                    var localRequire = function(nextName) {
                        return requireInternal(nextName, modulePath);
                    };

                    var factory = createFactory(moduleText);
                    factory(localModule, localModule.exports, localRequire);

                    if (typeof localModule.exports === 'function') {
                        try {
                            localModule.exports.__operit_toolpkg_module_path = modulePath;
                        } catch (_assignError) {
                        }
                    }
                    if (localModule.exports && typeof localModule.exports === 'object') {
                        var exportKeys = Object.keys(localModule.exports);
                        for (var i = 0; i < exportKeys.length; i += 1) {
                            var exportName = exportKeys[i];
                            var exportValue = localModule.exports[exportName];
                            if (typeof exportValue === 'function') {
                                try {
                                    exportValue.__operit_toolpkg_module_path = modulePath;
                                } catch (_assignError2) {
                                }
                            }
                        }
                    }
                    return localModule.exports;
                }

                function requireInternal(moduleName, fromPath) {
                    if (moduleName === 'lodash') {
                        return _;
                    }
                    if (moduleName === 'uuid') {
                        return {
                            v4: function() {
                                return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
                                    var r = Math.random() * 16 | 0;
                                    var v = c === 'x' ? r : ((r & 0x3) | 0x8);
                                    return v.toString(16);
                                });
                            }
                        };
                    }
                    if (moduleName === 'axios') {
                        return {
                            get: function(url, config) {
                                var requestParams = config ? Object.assign({}, { url: url }, config) : { url: url };
                                return toolCall("http_request", requestParams);
                            },
                            post: function(url, data, config) {
                                var requestParams = config ? Object.assign({}, { url: url, data: data }, config) : { url: url, data: data };
                                return toolCall("http_request", requestParams);
                            }
                        };
                    }

                    var request = String(moduleName || '').trim();
                    if (!(request.startsWith('.') || request.startsWith('/'))) {
                        return {};
                    }

                    var resolvedModulePath = resolveModulePath(request, fromPath || screenPath);
                    window.__operit_last_exec_stage = "require_module";
                    window.__operit_last_require_request = request;
                    window.__operit_last_require_from = String(fromPath || screenPath || '<root>');
                    window.__operit_last_require_resolved = resolvedModulePath;
                    window.__operit_last_module_path = resolvedModulePath;

                    if (registrationMode && /(^|\/)ui\/.+\.ui\.js$/i.test(String(resolvedModulePath || ''))) {
                        return createRegistrationScreenPlaceholder(resolvedModulePath);
                    }

                    var loadedModule = readToolPkgModule(packageTarget, resolvedModulePath);
                    if (!loadedModule) {
                        throw new Error(
                            'Cannot resolve module "' + request + '" from "' + (fromPath || screenPath || '<root>') + '"'
                        );
                    }
                    return executeModule(loadedModule.path, loadedModule.text, requireInternal);
                }

                var moduleResult = null;
                if (persistentModuleKey && window.__operitPersistentModuleCache[persistentModuleKey]) {
                    moduleResult = window.__operitPersistentModuleCache[persistentModuleKey];
                }

                if (!moduleResult) {
                    var module = { exports: {} };
                    var exports = module.exports;
                    var require = function(moduleName) {
                        window.__operit_last_exec_stage = "require_request";
                        window.__operit_last_require_request = String(moduleName || '');
                        window.__operit_last_require_from = String(screenPath || '<root>');
                        return requireInternal(moduleName, screenPath);
                    };

                    setStage('compile_main_script');
                    var mainFactory = createFactory($scriptJson);
                    setStage('execute_main_script');
                    mainFactory(module, exports, require);

                    moduleResult = { module: module, exports: exports };
                    if (persistentModuleKey) {
                        window.__operitPersistentModuleCache[persistentModuleKey] = moduleResult;
                    }
                }

                var rootModule = moduleResult.module;
                var rootExports = moduleResult.exports || (rootModule ? rootModule.exports : {}) || {};

                var inlineFunctionName = readStringParam('__operit_inline_function_name');
                var inlineFunctionSource = readStringParam('__operit_inline_function_source');
                if (inlineFunctionName && inlineFunctionSource) {
                    setStage('evaluate_inline_hook_function');
                    var inlineFunction = eval("(" + inlineFunctionSource + ")");
                    if (typeof inlineFunction !== 'function') {
                        throw new Error("inline hook source did not evaluate to function");
                    }
                    rootExports[inlineFunctionName] = inlineFunction;
                    if (rootModule && rootModule.exports) {
                        rootModule.exports[inlineFunctionName] = inlineFunction;
                    }
                }

                setStage('resolve_target_function');
                var targetFunction = null;
                if (rootExports && typeof rootExports[targetFunctionName] === 'function') {
                    targetFunction = rootExports[targetFunctionName];
                } else if (rootModule && rootModule.exports && typeof rootModule.exports[targetFunctionName] === 'function') {
                    targetFunction = rootModule.exports[targetFunctionName];
                } else if (typeof window[targetFunctionName] === 'function') {
                    targetFunction = window[targetFunctionName];
                }

                if (typeof targetFunction !== 'function') {
                    var availableFunctions = [];
                    if (rootExports && typeof rootExports === 'object') {
                        Object.keys(rootExports).forEach(function(key) {
                            if (typeof rootExports[key] === 'function') {
                                availableFunctions.push(key);
                            }
                        });
                    }
                    if (rootModule && rootModule.exports && typeof rootModule.exports === 'object') {
                        Object.keys(rootModule.exports).forEach(function(key) {
                            if (typeof rootModule.exports[key] === 'function' && availableFunctions.indexOf(key) < 0) {
                                availableFunctions.push(key);
                            }
                        });
                    }
                    emitError(
                        "Function '" +
                            targetFunctionName +
                            "' not found in script. Available functions: " +
                            (availableFunctions.length > 0 ? availableFunctions.join(", ") : "none")
                    );
                    return;
                }

                setStage('invoke_target_function');
                var previousModule = window.__operit_current_module;
                var previousExports = window.__operit_current_module_exports;
                window.__operit_current_module = rootModule;
                window.__operit_current_module_exports = rootExports;
                var functionResult = null;
                try {
                    functionResult = targetFunction(params);
                } finally {
                    window.__operit_current_module = previousModule;
                    window.__operit_current_module_exports = previousExports;
                }

                setStage('handle_function_result');
                var handledAsync = false;
                try {
                    if (typeof __handleAsync === 'function') {
                        handledAsync = __handleAsync(functionResult);
                    }
                } catch (asyncError) {
                    throw asyncError;
                }

                if (!handledAsync) {
                    function normalizeComposeDslResult(value) {
                        if (!value || typeof value !== 'object') {
                            return value;
                        }
                        var composeDsl = value.composeDsl;
                        if (!composeDsl || typeof composeDsl !== 'object') {
                            return value;
                        }
                        if (!Object.prototype.hasOwnProperty.call(composeDsl, 'screen')) {
                            return value;
                        }
                        var screenRef = composeDsl.screen;
                        var resolved = '';
                        if (typeof screenRef === 'function') {
                            var marker = screenRef.__operit_toolpkg_module_path;
                            if (typeof marker === 'string') {
                                resolved = marker.trim().replace(/\\\\/g, '/');
                            }
                        } else if (screenRef && typeof screenRef === 'object' && typeof screenRef.default === 'function') {
                            var defaultMarker = screenRef.default.__operit_toolpkg_module_path;
                            if (typeof defaultMarker === 'string') {
                                resolved = defaultMarker.trim().replace(/\\\\/g, '/');
                            }
                        } else if (typeof screenRef === 'string') {
                            throw new Error("composeDsl.screen must be a compose_dsl screen function, not a string path");
                        }
                        if (!resolved) {
                            throw new Error("composeDsl.screen is missing a toolpkg module path marker");
                        }
                        composeDsl.screen = resolved;
                        return value;
                    }

                    var normalizedResult = normalizeComposeDslResult(functionResult);
                    var serialized = JSON.stringify(normalizedResult);
                    emitResult(serialized);
                }
            } catch (error) {
                var runtimeContext =
                    typeof window.__operitBuildRuntimeContext === 'function'
                        ? String(window.__operitBuildRuntimeContext() || '')
                        : '';
                var runtimeContextText = runtimeContext ? ("\nRuntime Context: " + runtimeContext) : '';
                var stackText = error && error.stack ? ("\nStack: " + String(error.stack)) : '';
                emitError("Script error: " + (error && error.message ? error.message : String(error)) + runtimeContextText + stackText);
            }
        })();
    """.trimIndent()
}

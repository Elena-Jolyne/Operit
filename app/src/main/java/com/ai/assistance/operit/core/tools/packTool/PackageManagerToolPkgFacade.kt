package com.ai.assistance.operit.core.tools.packTool

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.nio.charset.StandardCharsets

internal class PackageManagerToolPkgFacade(
    private val packageManager: PackageManager
) {
    fun isToolPkgContainer(packageName: String): Boolean {
        packageManager.ensureInitialized()
        val normalizedPackageName = packageManager.normalizePackageName(packageName)
        return packageManager.toolPkgContainersInternal.containsKey(normalizedPackageName)
    }

    fun isToolPkgSubpackage(packageName: String): Boolean {
        packageManager.ensureInitialized()
        return packageManager.resolveToolPkgSubpackageRuntimeInternal(packageName) != null
    }

    fun getToolPkgContainerDetails(
        packageName: String,
        resolveContext: Context? = null
    ): PackageManager.ToolPkgContainerDetails? {
        packageManager.ensureInitialized()
        val normalizedPackageName = packageManager.normalizePackageName(packageName)
        val container = packageManager.toolPkgContainersInternal[normalizedPackageName] ?: return null
        val importedSet = packageManager.getImportedPackages().toSet()
        val localizationContext = resolveContext ?: packageManager.contextInternal
        val containerEnabled = importedSet.contains(container.packageName)

        val subpackages =
            container.subpackages.map { subpackage ->
                PackageManager.ToolPkgSubpackageInfo(
                    packageName = subpackage.packageName,
                    subpackageId = subpackage.subpackageId,
                    displayName = subpackage.displayName.resolve(localizationContext),
                    description = subpackage.description.resolve(localizationContext),
                    enabledByDefault = subpackage.enabledByDefault,
                    toolCount = subpackage.toolCount,
                    enabled = containerEnabled && importedSet.contains(subpackage.packageName)
                )
            }

        val result = PackageManager.ToolPkgContainerDetails(
            packageName = container.packageName,
            displayName = container.displayName.resolve(localizationContext),
            description = container.description.resolve(localizationContext),
            version = container.version,
            resourceCount = container.resources.size,
            uiModuleCount = container.uiModules.size,
            subpackages = subpackages
        )
        return result
    }

    fun getToolPkgToolboxUiModules(
        runtime: String = TOOLPKG_RUNTIME_COMPOSE_DSL,
        resolveContext: Context? = null
    ): List<PackageManager.ToolPkgToolboxUiModule> {
        packageManager.ensureInitialized()
        val localizationContext = resolveContext ?: packageManager.contextInternal
        fun resolveLocalized(text: com.ai.assistance.operit.core.tools.LocalizedText): String {
            return text.resolve(localizationContext)
        }
        val importedSet = packageManager.getImportedPackages().toSet()

        val result = packageManager.toolPkgContainersInternal.values
            .filter { container -> importedSet.contains(container.packageName) }
            .flatMap { container ->
                val containerDisplayName =
                    resolveLocalized(container.displayName).ifBlank { container.packageName }
                val containerDescription = resolveLocalized(container.description)
                container.uiModules
                    .filter { module ->
                        module.runtime.equals(runtime, ignoreCase = true)
                    }
                    .map { module ->
                        val moduleTitle =
                            resolveLocalized(module.title).trim().ifBlank { containerDisplayName }
                        PackageManager.ToolPkgToolboxUiModule(
                            containerPackageName = container.packageName,
                            toolPkgId = container.packageName,
                            uiModuleId = module.id,
                            runtime = module.runtime,
                            screen = module.screen,
                            title = moduleTitle,
                            description = containerDescription,
                            moduleSpec =
                                mapOf(
                                    "id" to module.id,
                                    "runtime" to module.runtime,
                                    "screen" to module.screen,
                                    "title" to moduleTitle,
                                    "toolPkgId" to container.packageName
                                )
                        )
                    }
            }
            .sortedWith(
                compareBy(
                    PackageManager.ToolPkgToolboxUiModule::title,
                    PackageManager.ToolPkgToolboxUiModule::containerPackageName,
                    PackageManager.ToolPkgToolboxUiModule::uiModuleId
                )
            )
        return result
    }

    fun getToolPkgAppLifecycleHooks(event: String): List<PackageManager.ToolPkgAppLifecycleHook> {
        packageManager.ensureInitialized()
        val normalizedEvent = event.trim().lowercase()
        if (normalizedEvent.isBlank()) {
            return emptyList()
        }
        val importedSet = packageManager.getImportedPackages().toSet()

        val result = packageManager.toolPkgContainersInternal.values
            .asSequence()
            .filter { container -> importedSet.contains(container.packageName) }
            .flatMap { container ->
                container.appLifecycleHooks.asSequence().map { hook ->
                    PackageManager.ToolPkgAppLifecycleHook(
                        containerPackageName = container.packageName,
                        hookId = hook.id,
                        event = hook.event,
                        functionName = hook.function
                    )
                }
            }
            .filter { hook -> hook.event.equals(normalizedEvent, ignoreCase = true) }
            .sortedWith(
                compareBy(
                    PackageManager.ToolPkgAppLifecycleHook::containerPackageName,
                    PackageManager.ToolPkgAppLifecycleHook::hookId
                )
            )
            .toList()
        return result
    }

    fun getToolPkgMessageProcessingPlugins(): List<PackageManager.ToolPkgMessageProcessingPlugin> {
        packageManager.ensureInitialized()
        val importedSet = packageManager.getImportedPackages().toSet()
        val result = packageManager.toolPkgContainersInternal.values
            .asSequence()
            .filter { container -> importedSet.contains(container.packageName) }
            .flatMap { container ->
                container.messageProcessingPlugins.asSequence().map { plugin ->
                    PackageManager.ToolPkgMessageProcessingPlugin(
                        containerPackageName = container.packageName,
                        pluginId = plugin.id,
                        functionName = plugin.function
                    )
                }
            }
            .sortedWith(
                compareBy(
                    PackageManager.ToolPkgMessageProcessingPlugin::containerPackageName,
                    PackageManager.ToolPkgMessageProcessingPlugin::pluginId
                )
            )
            .toList()
        return result
    }

    fun getToolPkgXmlRenderPlugins(tagName: String): List<PackageManager.ToolPkgXmlRenderPlugin> {
        packageManager.ensureInitialized()
        val normalizedTagName = tagName.trim().lowercase()
        if (normalizedTagName.isBlank()) {
            return emptyList()
        }
        val importedSet = packageManager.getImportedPackages().toSet()
        val result = packageManager.toolPkgContainersInternal.values
            .asSequence()
            .filter { container -> importedSet.contains(container.packageName) }
            .flatMap { container ->
                container.xmlRenderPlugins.asSequence().map { plugin ->
                    PackageManager.ToolPkgXmlRenderPlugin(
                        containerPackageName = container.packageName,
                        pluginId = plugin.id,
                        tag = plugin.tag,
                        functionName = plugin.function
                    )
                }
            }
            .filter { plugin -> plugin.tag.equals(normalizedTagName, ignoreCase = true) }
            .sortedWith(
                compareBy(
                    PackageManager.ToolPkgXmlRenderPlugin::containerPackageName,
                    PackageManager.ToolPkgXmlRenderPlugin::pluginId
                )
            )
            .toList()
        return result
    }

    fun getToolPkgInputMenuTogglePlugins(): List<PackageManager.ToolPkgInputMenuTogglePlugin> {
        packageManager.ensureInitialized()
        val importedSet = packageManager.getImportedPackages().toSet()
        val result = packageManager.toolPkgContainersInternal.values
            .asSequence()
            .filter { container -> importedSet.contains(container.packageName) }
            .flatMap { container ->
                container.inputMenuTogglePlugins.asSequence().map { plugin ->
                    PackageManager.ToolPkgInputMenuTogglePlugin(
                        containerPackageName = container.packageName,
                        pluginId = plugin.id,
                        functionName = plugin.function
                    )
                }
            }
            .sortedWith(
                compareBy(
                    PackageManager.ToolPkgInputMenuTogglePlugin::containerPackageName,
                    PackageManager.ToolPkgInputMenuTogglePlugin::pluginId
                )
            )
            .toList()
        return result
    }

    fun setToolPkgSubpackageEnabled(subpackagePackageName: String, enabled: Boolean): Boolean {
        packageManager.ensureInitialized()
        val normalizedPackageName = packageManager.normalizePackageName(subpackagePackageName)
        val subpackageRuntime = packageManager.toolPkgSubpackageByPackageNameInternal[normalizedPackageName]
        if (subpackageRuntime == null) {
            return false
        }

        val importedPackages = LinkedHashSet(packageManager.getImportedPackages())
        val subpackageStates = packageManager.getToolPkgSubpackageStatesInternal().toMutableMap()
        val containerEnabled = importedPackages.contains(subpackageRuntime.containerPackageName)

        subpackageStates[normalizedPackageName] = enabled

        if (containerEnabled && enabled) {
            importedPackages.add(normalizedPackageName)
        } else {
            importedPackages.remove(normalizedPackageName)
            packageManager.unregisterPackageTools(normalizedPackageName)
        }

        packageManager.saveImportedPackages(importedPackages.toList())
        packageManager.saveToolPkgSubpackageStates(subpackageStates)

        val stateSaved = packageManager.getToolPkgSubpackageStatesInternal()[normalizedPackageName] == enabled
        val importedMatches =
            if (containerEnabled) {
                packageManager.getImportedPackages().contains(normalizedPackageName) == enabled
            } else {
                !packageManager.getImportedPackages().contains(normalizedPackageName)
            }
        return stateSaved && importedMatches
    }

    fun findPreferredPackageNameForSubpackageId(
        subpackageId: String,
        preferImported: Boolean = true
    ): String? {
        packageManager.ensureInitialized()
        if (subpackageId.isBlank()) return null

        val directRuntime = packageManager.resolveToolPkgSubpackageRuntimeInternal(subpackageId)
        if (directRuntime != null) {
            if (preferImported) {
                if (packageManager.isPackageImported(directRuntime.packageName)) {
                    return directRuntime.packageName
                }
            }
            return directRuntime.packageName
        }

        val candidates =
            packageManager.toolPkgSubpackageByPackageNameInternal.values.filter {
                it.subpackageId.equals(subpackageId, ignoreCase = true)
            }

        if (candidates.isEmpty()) {
            return null
        }

        if (preferImported) {
            val importedCandidate = candidates.firstOrNull { packageManager.isPackageImported(it.packageName) }
            if (importedCandidate != null) {
                return importedCandidate.packageName
            }
        }

        return candidates.first().packageName
    }

    fun copyToolPkgResourceToFileBySubpackageId(
        subpackageId: String,
        resourceKey: String,
        destinationFile: File,
        preferImportedContainer: Boolean = true
    ): Boolean {
        packageManager.ensureInitialized()
        if (subpackageId.isBlank() || resourceKey.isBlank()) {
            return false
        }

        val directSubpackage = packageManager.resolveToolPkgSubpackageRuntimeInternal(subpackageId)
        val subpackages =
            if (directSubpackage != null) {
                listOf(directSubpackage)
            } else {
                packageManager.toolPkgSubpackageByPackageNameInternal.values.filter {
                    it.subpackageId.equals(subpackageId, ignoreCase = true)
                }
            }

        if (subpackages.isEmpty()) {
            return false
        }

        val candidateContainers =
            if (preferImportedContainer) {
                val imported = packageManager.getImportedPackages().toSet()
                val importedContainers =
                    subpackages
                        .map { it.containerPackageName }
                        .distinct()
                        .filter { imported.contains(it) }
                if (importedContainers.isNotEmpty()) {
                    importedContainers
                } else {
                    subpackages.map { it.containerPackageName }.distinct()
                }
            } else {
                subpackages.map { it.containerPackageName }.distinct()
            }

        candidateContainers.forEach { containerName ->
            if (copyToolPkgResourceToFile(containerName, resourceKey, destinationFile)) {
                return true
            }
        }

        return false
    }

    fun copyToolPkgResourceToFile(
        containerPackageName: String,
        resourceKey: String,
        destinationFile: File
    ): Boolean {
        packageManager.ensureInitialized()
        val normalizedContainerPackageName = packageManager.normalizePackageName(containerPackageName)
        val runtime = packageManager.toolPkgContainersInternal[normalizedContainerPackageName] ?: return false
        val importedSet = packageManager.getImportedPackages().toSet()
        if (!importedSet.contains(runtime.packageName)) {
            return false
        }
        val resource =
            runtime.resources.firstOrNull {
                it.key.equals(resourceKey, ignoreCase = true)
            } ?: return false

        return try {
            val bytes = packageManager.readToolPkgResourceBytes(runtime, resource.path) ?: return false
            val parent = destinationFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            destinationFile.outputStream().use { output ->
                output.write(bytes)
            }
            true
        } catch (e: Exception) {
            AppLogger.e("PackageManager", "Failed to export toolpkg resource: ${runtime.packageName}:${resource.key}", e)
            false
        }
    }

    fun getToolPkgResourceOutputFileName(
        packageNameOrSubpackageId: String,
        resourceKey: String,
        preferImportedContainer: Boolean = true
    ): String? {
        packageManager.ensureInitialized()
        val target = packageNameOrSubpackageId.trim()
        val key = resourceKey.trim()
        if (target.isBlank() || key.isBlank()) {
            return null
        }

        fun resolveFromContainer(containerName: String): String? {
            val normalizedContainerName = packageManager.normalizePackageName(containerName)
            val runtime = packageManager.toolPkgContainersInternal[normalizedContainerName] ?: return null
            val resource =
                runtime.resources.firstOrNull {
                    it.key.equals(key, ignoreCase = true)
                } ?: return null
            val fileName =
                resource.path.substringAfterLast('/').substringAfterLast('\\').trim()
            return fileName.ifBlank { null }
        }

        resolveFromContainer(target)?.let { return it }

        val directSubpackage = packageManager.resolveToolPkgSubpackageRuntimeInternal(target)
        if (directSubpackage != null) {
            resolveFromContainer(directSubpackage.containerPackageName)?.let { return it }
        }

        val subpackages =
            packageManager.toolPkgSubpackageByPackageNameInternal.values.filter {
                it.subpackageId.equals(target, ignoreCase = true)
            }
        if (subpackages.isEmpty()) {
            return null
        }

        val candidateContainers =
            if (preferImportedContainer) {
                val imported = packageManager.getImportedPackages().toSet()
                val importedContainers =
                    subpackages
                        .map { it.containerPackageName }
                        .distinct()
                        .filter { imported.contains(it) }
                if (importedContainers.isNotEmpty()) {
                    importedContainers
                } else {
                    subpackages.map { it.containerPackageName }.distinct()
                }
            } else {
                subpackages.map { it.containerPackageName }.distinct()
            }

        candidateContainers.forEach { containerName ->
            resolveFromContainer(containerName)?.let { return it }
        }

        return null
    }

    fun getToolPkgComposeDslScriptBySubpackageId(
        subpackageId: String,
        uiModuleId: String? = null,
        preferImportedContainer: Boolean = true
    ): String? {
        packageManager.ensureInitialized()
        if (subpackageId.isBlank()) {
            return null
        }

        val directSubpackage = packageManager.resolveToolPkgSubpackageRuntimeInternal(subpackageId)
        val subpackages =
            if (directSubpackage != null) {
                listOf(directSubpackage)
            } else {
                packageManager.toolPkgSubpackageByPackageNameInternal.values.filter {
                    it.subpackageId.equals(subpackageId, ignoreCase = true)
                }
            }

        if (subpackages.isEmpty()) {
            return null
        }

        val candidateContainers =
            if (preferImportedContainer) {
                val imported = packageManager.getImportedPackages().toSet()
                subpackages
                    .map { it.containerPackageName }
                    .distinct()
                    .filter { imported.contains(it) }
            } else {
                subpackages.map { it.containerPackageName }.distinct()
            }

        candidateContainers.forEach { containerName ->
            val script = getToolPkgComposeDslScript(containerName, uiModuleId)
            if (!script.isNullOrBlank()) {
                return script
            }
        }

        return null
    }

    fun getToolPkgComposeDslScript(
        containerPackageName: String,
        uiModuleId: String? = null
    ): String? {
        packageManager.ensureInitialized()
        val normalizedContainerPackageName = packageManager.normalizePackageName(containerPackageName)
        val runtime = packageManager.toolPkgContainersInternal[normalizedContainerPackageName] ?: return null
        val importedSet = packageManager.getImportedPackages().toSet()
        if (!importedSet.contains(runtime.packageName)) {
            return null
        }

        val uiModule =
            if (!uiModuleId.isNullOrBlank()) {
                runtime.uiModules.firstOrNull { module ->
                    module.id.equals(uiModuleId, ignoreCase = true) &&
                        module.runtime.equals(TOOLPKG_RUNTIME_COMPOSE_DSL, ignoreCase = true)
                }
            } else {
                runtime.uiModules.firstOrNull { module ->
                    module.runtime.equals(TOOLPKG_RUNTIME_COMPOSE_DSL, ignoreCase = true)
                }
            } ?: return null

        if (uiModule.screen.isBlank()) {
            return null
        }

        return try {
            val bytes = packageManager.readToolPkgResourceBytes(runtime, uiModule.screen) ?: return null
            bytes.toString(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            AppLogger.e(
                "PackageManager",
                "Failed to read toolpkg compose_dsl script: ${runtime.packageName}:${uiModule.id}",
                e
            )
            null
        }
    }

    fun getToolPkgComposeDslScreenPath(
        containerPackageName: String,
        uiModuleId: String? = null
    ): String? {
        packageManager.ensureInitialized()
        val normalizedContainerPackageName = packageManager.normalizePackageName(containerPackageName)
        val runtime = packageManager.toolPkgContainersInternal[normalizedContainerPackageName] ?: return null
        val importedSet = packageManager.getImportedPackages().toSet()
        if (!importedSet.contains(runtime.packageName)) {
            return null
        }

        val uiModule =
            if (!uiModuleId.isNullOrBlank()) {
                runtime.uiModules.firstOrNull { module ->
                    module.id.equals(uiModuleId, ignoreCase = true) &&
                        module.runtime.equals(TOOLPKG_RUNTIME_COMPOSE_DSL, ignoreCase = true)
                }
            } else {
                runtime.uiModules.firstOrNull { module ->
                    module.runtime.equals(TOOLPKG_RUNTIME_COMPOSE_DSL, ignoreCase = true)
                }
            } ?: return null

        return uiModule.screen.trim().ifBlank { null }
    }

    fun runToolPkgMainHook(
        containerPackageName: String,
        functionName: String,
        event: String,
        pluginId: String? = null,
        eventPayload: Map<String, Any?> = emptyMap()
    ): Result<Any?> {
        return runCatching {
            val normalizedPluginId = pluginId?.trim().orEmpty().ifBlank { null }
            val normalizedContainerPackageName = packageManager.normalizePackageName(containerPackageName)
            val runtime =
                packageManager.toolPkgContainersInternal[normalizedContainerPackageName]
                    ?: throw IllegalArgumentException("Toolpkg container not found: $containerPackageName")
            val script =
                packageManager.getToolPkgMainScriptInternal(runtime.packageName)
                    ?: throw IllegalStateException("Toolpkg main script is unavailable: ${runtime.packageName}")
            val functionSource =
                resolveToolPkgFunctionSource(
                    runtime = runtime,
                    functionName = functionName,
                    event = event
                )

            val timestampMs = System.currentTimeMillis()
        val params = mutableMapOf<String, Any?>(
            "event" to event,
            "eventName" to event,
            "eventPayload" to eventPayload,
            "timestampMs" to timestampMs,
            "functionName" to functionName,
            "toolPkgId" to runtime.packageName,
            "containerPackageName" to runtime.packageName,
            "__operit_ui_package_name" to runtime.packageName,
            "__operit_persistent_module_key" to "toolpkg_main:" + runtime.packageName
        )
            if (!normalizedPluginId.isNullOrBlank()) {
                params["pluginId"] = normalizedPluginId
            }
            if (!functionSource.isNullOrBlank()) {
                params["__operit_inline_function_name"] = functionName
                params["__operit_inline_function_source"] = functionSource
            }
            val executionContextKey = resolveToolPkgExecutionContextKey(runtime.packageName, params)
            val executionEngine = packageManager.getToolPkgExecutionEngine(executionContextKey)
            executionEngine.executeScriptFunction(
                script = script,
                functionName = functionName,
                params = params
            )
        }.onFailure { error ->
            val normalizedPluginId = pluginId?.trim().orEmpty().ifBlank { null }
            val pluginPart = if (normalizedPluginId.isNullOrBlank()) "" else ", plugin=$normalizedPluginId"
            AppLogger.e(
                "PackageManagerToolPkgFacade",
                "runToolPkgMainHook failed: container=$containerPackageName, function=$functionName, event=$event$pluginPart",
                error
            )
        }
    }

    private fun resolveToolPkgFunctionSource(
        runtime: ToolPkgContainerRuntime,
        functionName: String,
        event: String
    ): String? {
        val normalizedFunction = functionName.trim()
        if (normalizedFunction.isBlank()) {
            return null
        }
        val normalizedEvent = event.trim().lowercase()

        runtime.appLifecycleHooks.firstOrNull { hook ->
            hook.function == normalizedFunction && hook.event.equals(normalizedEvent, ignoreCase = true)
        }?.functionSource?.let { return it }

        if (normalizedEvent == TOOLPKG_EVENT_MESSAGE_PROCESSING) {
            runtime.messageProcessingPlugins.firstOrNull { hook ->
                hook.function == normalizedFunction
            }?.functionSource?.let { return it }
        }

        if (normalizedEvent == TOOLPKG_EVENT_XML_RENDER) {
            runtime.xmlRenderPlugins.firstOrNull { hook ->
                hook.function == normalizedFunction
            }?.functionSource?.let { return it }
        }

        if (normalizedEvent == TOOLPKG_EVENT_INPUT_MENU_TOGGLE) {
            runtime.inputMenuTogglePlugins.firstOrNull { hook ->
                hook.function == normalizedFunction
            }?.functionSource?.let { return it }
        }

        return null
    }

    private fun resolveToolPkgExecutionContextKey(
        containerPackageName: String,
        params: Map<String, Any?>
    ): String {
        val explicitContextKey =
            sequenceOf(
                params["__operit_execution_context_key"],
                params["__operit_persistent_module_key"]
            )
                .mapNotNull { it?.toString()?.trim() }
                .firstOrNull { it.isNotBlank() }
        if (!explicitContextKey.isNullOrBlank()) {
            return explicitContextKey
        }
        return "toolpkg_main:$containerPackageName"
    }

    fun readToolPkgTextResource(
        packageNameOrSubpackageId: String,
        resourcePath: String,
        preferImportedContainer: Boolean = true
    ): String? {
        packageManager.ensureInitialized()
        val target = packageNameOrSubpackageId.trim()
        val normalizedPath =
            resourcePath
                .trim()
                .replace('\\', '/')
                .trimStart('/')

        if (target.isBlank() || normalizedPath.isBlank()) {
            return null
        }

        val containerRuntime = packageManager.toolPkgContainersInternal[target]
        if (containerRuntime != null) {
            val importedSet = packageManager.getImportedPackages().toSet()
            if (!importedSet.contains(containerRuntime.packageName)) {
                return null
            }
            return packageManager.readToolPkgResourceBytes(containerRuntime, normalizedPath)
                ?.toString(StandardCharsets.UTF_8)
        }

        val directSubpackageRuntime = packageManager.resolveToolPkgSubpackageRuntimeInternal(target)
        if (directSubpackageRuntime != null) {
            val directContainer = packageManager.toolPkgContainersInternal[directSubpackageRuntime.containerPackageName]
            if (directContainer != null) {
                val importedSet = packageManager.getImportedPackages().toSet()
                if (!importedSet.contains(directContainer.packageName)) {
                    return null
                }
                return packageManager.readToolPkgResourceBytes(directContainer, normalizedPath)
                    ?.toString(StandardCharsets.UTF_8)
            }
        }

        val subpackages =
            packageManager.toolPkgSubpackageByPackageNameInternal.values.filter {
                it.subpackageId.equals(target, ignoreCase = true)
            }
        if (subpackages.isEmpty()) {
            return null
        }

        val candidateContainers =
            if (preferImportedContainer) {
                val imported = packageManager.getImportedPackages().toSet()
                subpackages
                    .map { it.containerPackageName }
                    .distinct()
                    .filter { imported.contains(it) }
            } else {
                subpackages.map { it.containerPackageName }.distinct()
            }

        candidateContainers.forEach { containerName ->
            val runtime = packageManager.toolPkgContainersInternal[containerName] ?: return@forEach
            val text =
                packageManager.readToolPkgResourceBytes(runtime, normalizedPath)
                    ?.toString(StandardCharsets.UTF_8)
            if (!text.isNullOrEmpty()) {
                return text
            }
        }

        return null
    }
}

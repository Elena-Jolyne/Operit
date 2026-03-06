package com.ai.assistance.operit.core.tools.packTool

/**
 * Mirror of ToolPkg xml_render hook result shape used by JS toolpkg.
 */
data class ToolPkgXmlRenderHookComposeDslResult(
    val screen: String,
    val state: Map<String, Any?> = emptyMap(),
    val memo: Map<String, Any?> = emptyMap(),
    val moduleSpec: Map<String, Any?>? = null
)

data class ToolPkgXmlRenderHookObjectResult(
    val handled: Boolean? = null,
    val text: String? = null,
    val content: String? = null,
    val composeDsl: ToolPkgXmlRenderHookComposeDslResult? = null
)

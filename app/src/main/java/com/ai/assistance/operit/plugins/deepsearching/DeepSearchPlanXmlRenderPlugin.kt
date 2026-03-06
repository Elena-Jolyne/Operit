package com.ai.assistance.operit.plugins.deepsearching

import androidx.compose.ui.graphics.Color
import com.ai.assistance.operit.ui.common.markdown.XmlRenderPlugin
import com.ai.assistance.operit.ui.common.markdown.XmlRenderResult
import com.ai.assistance.operit.util.stream.Stream
import android.content.Context

object DeepSearchPlanXmlRenderPlugin : XmlRenderPlugin {
    override val id: String = "builtin.deepsearch.plan.xml-renderer"

    override fun supports(tagName: String): Boolean {
        return tagName == "plan"
    }

    override suspend fun resolve(
        context: Context,
        xmlContent: String,
        tagName: String,
        textColor: Color,
        xmlStream: Stream<String>?
    ): XmlRenderResult? {
        return XmlRenderResult.ComposableRender { modifier, _, _ ->
            PlanExecutionRenderer(
                content = xmlContent,
                modifier = modifier
            )
        }
    }
}

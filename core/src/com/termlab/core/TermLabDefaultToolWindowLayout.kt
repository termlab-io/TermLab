package com.termlab.core

import com.intellij.toolWindow.DefaultToolWindowLayoutBuilder
import com.intellij.toolWindow.DefaultToolWindowLayoutExtension
import com.intellij.toolWindow.ToolWindowDescriptor.ToolWindowContentUiType

class TermLabDefaultToolWindowLayout : DefaultToolWindowLayoutExtension {

    override fun buildV1Layout(builder: DefaultToolWindowLayoutBuilder) = applyLayout(builder)

    override fun buildV2Layout(builder: DefaultToolWindowLayoutBuilder) = applyLayout(builder)

    private fun applyLayout(builder: DefaultToolWindowLayoutBuilder) {
        builder.removeAll()

        builder.left.addOrUpdate("Project") {
            weight = 0.25f
            contentUiType = ToolWindowContentUiType.COMBO
        }

        builder.bottom.addOrUpdate("Version Control")
        builder.bottom.addOrUpdate("Find")
        builder.bottom.addOrUpdate("Run")
        builder.bottom.addOrUpdate("Debug") { weight = 0.4f }
        builder.bottom.addOrUpdate("Inspection") { weight = 0.4f }
        builder.bottom.addOrUpdate("TermLab SFTP") {
            isVisible = true
            weight = 0.3225f
        }
        builder.bottom.addOrUpdate("SFTP") {
            isVisible = true
            weight = 0.3305f
        }

        builder.right.addOrUpdate("Notifications") { weight = 0.25f }
        builder.right.addOrUpdate("Hosts") {
            isVisible = true
            weight = 0.27821428f
            sideWeight = 0.50083613f
        }
        builder.right.addOrUpdate("Tunnels") {
            isVisible = true
            weight = 0.27821428f
            sideWeight = 0.49916387f
            isSplit = true
        }
    }
}

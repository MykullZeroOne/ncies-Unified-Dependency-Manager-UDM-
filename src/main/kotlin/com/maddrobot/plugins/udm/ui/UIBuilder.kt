package com.maddrobot.plugins.udm.ui

import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * madd robot tech
 * @LastModified: 2026-01-30
 * @since 2025-01-22
 */
fun boxPanel(init: JPanel.() -> Unit) = object : JPanel() {
    init {

        alignmentX = LEFT_ALIGNMENT
        init()
    }
}

fun borderPanel(init: JPanel.() -> Unit) = object : JPanel() {
    init {
        layout = BorderLayout(0, 0)
        init()
    }
}

fun scrollPanel(init: JScrollPane.() -> Unit) = object : JScrollPane() {
    init {

        border = JBUI.Borders.empty()
        viewportBorder = JBUI.Borders.empty()

        horizontalScrollBarPolicy = HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = VERTICAL_SCROLLBAR_ALWAYS

        init()
    }
}

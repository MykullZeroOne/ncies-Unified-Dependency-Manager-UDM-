package com.maddrobot.plugins.udm.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * Badge/pill component for displaying package status indicators.
 * Provides visual indicators for update, outdated, vulnerable, transitive,
 * prerelease, and deprecated states.
 */
class StatusBadge(
    private val badgeType: BadgeType,
    text: String? = null
) : JLabel(text ?: badgeType.defaultText) {

    /**
     * Badge types with associated color schemes.
     */
    enum class BadgeType(
        val defaultText: String,
        val backgroundColor: JBColor,
        val foregroundColor: JBColor,
        val borderColor: JBColor? = null
    ) {
        /** Green badge for available updates */
        UPDATE(
            "Update",
            JBColor(Color(0x4CAF50), Color(0x81C784)),
            JBColor.WHITE,
            null
        ),

        /** Orange badge for outdated packages */
        OUTDATED(
            "Outdated",
            JBColor(Color(0xFF9800), Color(0xFFB74D)),
            JBColor.WHITE,
            null
        ),

        /** Red badge for security vulnerabilities */
        VULNERABLE(
            "Vulnerable",
            JBColor(Color(0xF44336), Color(0xE57373)),
            JBColor.WHITE,
            null
        ),

        /** Gray badge for transitive/implicit dependencies */
        TRANSITIVE(
            "Transitive",
            JBColor(Color(0x9E9E9E), Color(0x757575)),
            JBColor.WHITE,
            null
        ),

        /** Purple badge for prerelease versions (alpha/beta/RC) */
        PRERELEASE(
            "Prerelease",
            JBColor(Color(0x9C27B0), Color(0xBA68C8)),
            JBColor.WHITE,
            null
        ),

        /** Dark gray badge for deprecated packages */
        DEPRECATED(
            "Deprecated",
            JBColor(Color(0x616161), Color(0x424242)),
            JBColor.WHITE,
            null
        ),

        /** Blue badge for installed packages */
        INSTALLED(
            "Installed",
            JBColor(Color(0x2196F3), Color(0x64B5F6)),
            JBColor.WHITE,
            null
        ),

        /** Light badge with border for version display */
        VERSION(
            "",
            JBColor(Color(0xF5F5F5), Color(0x424242)),
            JBColor(Color(0x424242), Color(0xBDBDBD)),
            JBColor(Color(0xBDBDBD), Color(0x616161))
        ),

        /** Green outline badge for new/latest version */
        LATEST(
            "Latest",
            JBColor(Color(0xE8F5E9), Color(0x1B5E20)),
            JBColor(Color(0x2E7D32), Color(0x81C784)),
            JBColor(Color(0x4CAF50), Color(0x4CAF50))
        ),

        /** Blue outline badge for plugins */
        PLUGIN(
            "Plugin",
            JBColor(Color(0xE3F2FD), Color(0x0D47A1)),
            JBColor(Color(0x1976D2), Color(0x64B5F6)),
            JBColor(Color(0x2196F3), Color(0x2196F3))
        )
    }

    companion object {
        private const val CORNER_RADIUS = 10
        private const val HORIZONTAL_PADDING = 6
        private const val VERTICAL_PADDING = 2

        /**
         * Create an update badge with version info.
         */
        fun updateBadge(version: String): StatusBadge {
            return StatusBadge(BadgeType.UPDATE, "↑ $version")
        }

        /**
         * Create a version badge.
         */
        fun versionBadge(version: String): StatusBadge {
            return StatusBadge(BadgeType.VERSION, version)
        }

        /**
         * Create a badge based on package status.
         */
        fun forPackageStatus(
            hasUpdate: Boolean = false,
            isVulnerable: Boolean = false,
            isTransitive: Boolean = false,
            isDeprecated: Boolean = false,
            isPrerelease: Boolean = false,
            updateVersion: String? = null
        ): StatusBadge? {
            return when {
                isVulnerable -> StatusBadge(BadgeType.VULNERABLE)
                hasUpdate && updateVersion != null -> updateBadge(updateVersion)
                hasUpdate -> StatusBadge(BadgeType.UPDATE)
                isDeprecated -> StatusBadge(BadgeType.DEPRECATED)
                isTransitive -> StatusBadge(BadgeType.TRANSITIVE)
                isPrerelease -> StatusBadge(BadgeType.PRERELEASE)
                else -> null
            }
        }
    }

    init {
        isOpaque = false
        font = font.deriveFont(Font.PLAIN, 10f)
        foreground = badgeType.foregroundColor
        horizontalAlignment = CENTER
        border = JBUI.Borders.empty(VERTICAL_PADDING, HORIZONTAL_PADDING)
    }

    override fun paintComponent(g: Graphics) {
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val width = width.toFloat()
        val height = height.toFloat()

        // Draw background
        val shape = RoundRectangle2D.Float(
            0f, 0f, width - 1, height - 1,
            CORNER_RADIUS.toFloat(), CORNER_RADIUS.toFloat()
        )

        g2d.color = badgeType.backgroundColor
        g2d.fill(shape)

        // Draw border if specified
        badgeType.borderColor?.let { borderColor ->
            g2d.color = borderColor
            g2d.stroke = BasicStroke(1f)
            g2d.draw(shape)
        }

        g2d.dispose()

        // Draw text
        super.paintComponent(g)
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val textWidth = fm.stringWidth(text ?: "")
        val textHeight = fm.height
        return Dimension(
            textWidth + (HORIZONTAL_PADDING * 2) + 2,
            textHeight + (VERTICAL_PADDING * 2)
        )
    }

    override fun getMinimumSize(): Dimension = preferredSize
}

/**
 * A panel that can display multiple status badges horizontally.
 */
class StatusBadgePanel : JComponent() {
    private val badges = mutableListOf<StatusBadge>()

    init {
        layout = FlowLayout(FlowLayout.LEFT, 4, 0)
        isOpaque = false
    }

    fun addBadge(badge: StatusBadge) {
        badges.add(badge)
        add(badge)
    }

    fun clearBadges() {
        badges.clear()
        removeAll()
    }

    fun setBadges(vararg newBadges: StatusBadge?) {
        clearBadges()
        newBadges.filterNotNull().forEach { addBadge(it) }
        revalidate()
        repaint()
    }

    /**
     * Set badges based on package status.
     */
    fun setPackageStatus(
        hasUpdate: Boolean = false,
        isVulnerable: Boolean = false,
        isTransitive: Boolean = false,
        isDeprecated: Boolean = false,
        isPrerelease: Boolean = false,
        updateVersion: String? = null,
        installedVersion: String? = null
    ) {
        clearBadges()

        // Add version badge first if installed
        if (installedVersion != null) {
            addBadge(StatusBadge.versionBadge(installedVersion))
        }

        // Add status badges in priority order
        if (isVulnerable) {
            addBadge(StatusBadge(StatusBadge.BadgeType.VULNERABLE))
        }
        if (hasUpdate && updateVersion != null) {
            addBadge(StatusBadge.updateBadge(updateVersion))
        } else if (hasUpdate) {
            addBadge(StatusBadge(StatusBadge.BadgeType.UPDATE))
        }
        if (isDeprecated) {
            addBadge(StatusBadge(StatusBadge.BadgeType.DEPRECATED))
        }
        if (isTransitive) {
            addBadge(StatusBadge(StatusBadge.BadgeType.TRANSITIVE))
        }
        if (isPrerelease) {
            addBadge(StatusBadge(StatusBadge.BadgeType.PRERELEASE))
        }

        revalidate()
        repaint()
    }
}

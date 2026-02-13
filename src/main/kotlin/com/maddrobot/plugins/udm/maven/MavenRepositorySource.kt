package com.maddrobot.plugins.udm.maven

import com.maddrobot.plugins.udm.util.Icons
import javax.swing.Icon

/**
 * Maven repository source
 *
 * madd robot tech
 * @LastModified: 2025-07-07
 * @since 2025-01-23
 */
enum class MavenRepositorySource(val displayName: String, val icon: Icon) {
    CENTRAL("Central", Icons.CENTRAL_REPOSITORY.getThemeBasedIcon()),
    LOCAL("Local", Icons.LOCAL_REPOSITORY.getThemeBasedIcon()),
    NEXUS("Nexus", Icons.NEXUS_REPOSITORY.getThemeBasedIcon())
}

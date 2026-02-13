package com.maddrobot.plugins.udm.maven

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Maven dependency model retrieved from a Nexus private repository
 *
 * madd robot tech
 * @LastModified: 2025-07-07
 * @since 2025-07-07
 */
data class NexusDependency(
    override val groupId: String,
    override val artifactId: String,
    override val version: String,
    val downloadInfos: List<DownloadInfo>,
    val uploaderIp: String
) : Dependency(groupId, artifactId, version)

data class DownloadInfo(
    val extension: String,
    val downloadUrl: String
)

@Serializable
data class NexusSearchResult(
    @SerialName("items")
    val items: List<NexusItem>
)

@Serializable
data class NexusItem(
    @SerialName("assets")
    val assets: List<NexusAsset>
)

@Serializable
data class NexusAsset(
    @SerialName("downloadUrl")
    val downloadUrl: String,
    @SerialName("uploaderIp")
    val uploaderIp: String,
    @SerialName("maven2")
    val maven2: Maven2Info
)

@Serializable
data class Maven2Info(
    @SerialName("groupId")
    val groupId: String,
    @SerialName("artifactId")
    val artifactId: String,
    @SerialName("version")
    val version: String,
    @SerialName("extension")
    val extension: String
)

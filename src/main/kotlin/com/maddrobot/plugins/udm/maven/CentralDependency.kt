package com.maddrobot.plugins.udm.maven

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a Maven dependency retrieved from a central repository, encapsulating information
 * about the group ID, artifact ID, version, packaging, timestamp, and available extensions or classifiers.
 *
 * This class extends the [Dependency] class, inheriting the group ID, artifact ID, and version properties.
 *
 * @property id Unique identifier for the dependency.
 * @property groupId Group identifier of the dependency, serialized as "g".
 * @property artifactId Artifact identifier of the dependency, serialized as "a".
 * @property version Version of the dependency, serialized as "v".
 * @property packaging Specifies the packaging type of the artifact (e.g., jar, pom).
 * @property timestamp Timestamp of the dependency, typically indicating when it was published.
 * @property ec A list of extensions or classifiers associated with the dependency.
 *         This includes information such as source jars, documentation jars, and POM files,
 *         representing the available artifacts for the dependency.
 */
@Serializable
data class CentralDependency(
    val id: String,
    @SerialName("g")
    override val groupId: String,
    @SerialName("a")
    override val artifactId: String,
    @SerialName("v")
    override val version: String,
    @SerialName("p")
    val packaging: String,
    val timestamp: Long,
    val ec: List<String>
) : Dependency(groupId, artifactId, version)

@Serializable
data class ArtifactResponse(
    val numFound: Int,
    @SerialName("docs")
    val centralDependencies: List<CentralDependency>
)

@Serializable
data class MavenSearchResult(
    val response: ArtifactResponse
)

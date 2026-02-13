package com.maddrobot.plugins.udm.npm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the result of a search query performed on the NPM Registry API.
 *
 * The primary purpose of this class is to encapsulate the response data returned
 * by the API when searching for packages based on a specific keyword or query.
 *
 * @property objects A list of `NpmObject` instances, where each object provides details about
 * an individual package found as part of the search results. These details include
 * metadata, downloads information, and dependent counts.
 */
@Serializable
data class NpmRegistrySearchResult(
    val objects: List<NpmObject>
)

/**
 * Represents an NPM object containing metadata about a package, its usage statistics, and related information.
 *
 * This class is used to model data fetched from the NPM registry API. It encapsulates:
 * - Download statistics for the package.
 * - The number of other packages that depend on this package.
 * - Detailed metadata about the package itself, such as name, version, description, license, and publisher information.
 *
 * Instances of this class are commonly found in the results of NPM registry search operations.
 *
 * @property downloads The number of downloads the package has within specified time periods (weekly, monthly).
 * @property dependents The number of dependent packages relying on this package.
 * @property package Detailed metadata about the NPM package, including its name, version, license, and publisher details.
 */
@Serializable
data class NpmObject(
    val downloads: Downloads,
    val dependents: Int,
    val `package`: Package,
)

/**
 * Represents download statistics for an NPM package.
 *
 * This data class encapsulates information related to the number of times
 * an NPM package has been downloaded over specific time periods.
 *
 * @property monthly The number of downloads in the last month.
 * @property weekly The number of downloads in the past week.
 */
@Serializable
data class Downloads(
    val monthly: Int,
    val weekly: Int
)

/**
 * Represents a package in the NPM ecosystem.
 *
 * This data class contains metadata about a specific package, including its name,
 * version, description, licensing information, publisher details, and the date it
 * was published. It is used to model the structure of package objects retrieved or
 * interacted with programmatically.
 *
 * @property packageName The official name of the package.
 * @property version The version of the package as per semantic versioning.
 * @property description A brief overview of the package functionality.
 * @property publisher Information about the publisher of the package.
 * @property license The type of license under which the package is published.
 * @property date The publication date of the package.
 */
@Serializable
data class Package(
    @SerialName("name")
    val packageName: String,
    val version: String,
    val description: String = "N/A",
    val publisher: Publisher? = null,
    val license: String = "N/A",
    val date: String
)

/**
 * Represents the publisher of a package.
 *
 * This data class encapsulates information about the publisher of an NPM package.
 * It includes the publisher's email and username, which are provided as part
 * of the package metadata.
 *
 * This class is commonly used in tandem with the `Package` class to enrich package
 * details with publisher-specific information.
 *
 * @property email The email address of the publisher.
 * @property username The username of the publisher.
 */
@Serializable
data class Publisher(
    val email: String,
    val username: String
)

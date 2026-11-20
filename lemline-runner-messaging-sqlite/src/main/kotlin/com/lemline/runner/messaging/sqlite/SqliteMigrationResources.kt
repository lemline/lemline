// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.sqlite

import org.flywaydb.core.api.ResourceProvider
import org.flywaydb.core.api.resource.LoadableResource

/** Load bundled SQL by name in both JVM packages and native images. */
internal object SqliteMigrationResources : ResourceProvider {
    private const val DIRECTORY = "db/migration/messaging/sqlite"
    private val resources by lazy {
        val index = checkNotNull(javaClass.getResourceAsStream("/sqlite-messaging-migrations.txt")) {
            "Missing SQLite messaging migration index"
        }
        index.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map(::Migration).toList()
        }.also { check(it.isNotEmpty()) { "Empty SQLite messaging migration index" } }
    }

    override fun getResource(name: String): LoadableResource? =
        resources.firstOrNull { it.filename == name || it.absolutePath == name }

    override fun getResources(prefix: String, suffixes: Array<String>): Collection<LoadableResource> =
        resources.filter { it.filename.startsWith(prefix) && suffixes.any(it.filename::endsWith) }

    private class Migration(private val name: String) : LoadableResource() {
        override fun getFilename() = name
        override fun getAbsolutePath() = "$DIRECTORY/$name"
        override fun getAbsolutePathOnDisk() = absolutePath
        override fun getRelativePath() = name
        override fun read() = checkNotNull(javaClass.getResourceAsStream("/$absolutePath")) {
            "Missing SQLite messaging migration $absolutePath"
        }.bufferedReader()
    }
}

// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import java.io.StringReader
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode

/**
 * Hides secret values in a YAML configuration file while keeping its text as written:
 * comments, order, indentation and line numbers are preserved.
 */
object ConfigFileRedactor {
    const val MASK = "••••••••"

    /** A key set in the file, with the 1-based line where it is written. */
    data class FileKey(val key: String, val line: Int, val secret: Boolean)

    data class Result(val text: String, val keys: List<FileKey>)

    private const val MAX_DEPTH = 64

    /** Leaf names that hold credentials without ending in password, secret or token. */
    private val SECRET_LEAVES = setOf(
        "jaas-config", "sasl-jaas-config", "api-key", "access-key", "secret-key", "credentials", "passphrase",
    )

    /** A URL carrying `user:password@`. */
    private val URL_WITH_PASSWORD = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*://[^/@\s:]*:[^/@\s]*@""")

    /** True for keys whose value must never leave the process. Paths such as `tls.private-key` are not secrets. */
    fun isSecret(key: String): Boolean {
        val leaf = key.substringAfterLast('.').substringBefore('[').lowercase()
        return leaf == "password" || leaf.endsWith("-password") ||
            leaf == "secret" || leaf.endsWith("-secret") ||
            leaf == "token" || leaf.endsWith("-token") ||
            leaf in SECRET_LEAVES
    }

    /** True for a value that embeds credentials whatever its key, such as `postgresql://app:pw@db/lemline`. */
    fun hasEmbeddedPassword(value: String?): Boolean = value != null && URL_WITH_PASSWORD.containsMatchIn(value.trim())

    /**
     * Returns the redacted text and the keys it sets, or null when the text cannot be parsed:
     * an unparsable file is never returned, since its secrets could not be located.
     */
    fun redact(text: String): Result? {
        val root = try {
            Yaml(LoaderOptions()).compose(StringReader(text))
        } catch (_: Exception) {
            return null
        } ?: return Result(text, emptyList())

        val secretNodes = LinkedHashSet<Node>()
        val keys = mutableListOf<FileKey>()
        walk(root, "", null, false, secretNodes, keys, 0)
        return Result(mask(text, secretNodes), keys.distinctBy { it.key to it.line })
    }

    private fun walk(
        node: Node,
        path: String,
        keyLine: Int?,
        secret: Boolean,
        secretNodes: MutableSet<Node>,
        keys: MutableList<FileKey>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        when (node) {
            is MappingNode -> node.value.forEach { tuple ->
                val keyNode = tuple.keyNode as? ScalarNode ?: return@forEach
                if (keyNode.value == "<<") {
                    // Merge key: the merged entries belong to this mapping.
                    walk(tuple.valueNode, path, keyLine, secret, secretNodes, keys, depth + 1)
                    return@forEach
                }
                val childPath = if (path.isEmpty()) keyNode.value else "$path.${keyNode.value}"
                val childSecret = secret || isSecret(childPath)
                walk(tuple.valueNode, childPath, keyNode.startMark.line + 1, childSecret, secretNodes, keys, depth + 1)
            }

            is SequenceNode -> {
                if (path.isNotEmpty()) keys += FileKey(path, keyLine ?: (node.startMark.line + 1), secret)
                node.value.forEachIndexed { index, item ->
                    if (item is ScalarNode) {
                        if (secret || hasEmbeddedPassword(item.value)) secretNodes += item
                    } else {
                        walk(item, "$path[$index]", item.startMark.line + 1, secret, secretNodes, keys, depth + 1)
                    }
                }
            }

            is ScalarNode -> {
                val hidden = secret || hasEmbeddedPassword(node.value)
                if (path.isNotEmpty()) keys += FileKey(path, keyLine ?: (node.startMark.line + 1), hidden)
                // An alias resolves to the anchored node itself, so masking it also masks the anchor's value.
                if (hidden) secretNodes += node
            }
        }
    }

    private fun mask(text: String, secretNodes: Set<Node>): String {
        if (secretNodes.isEmpty()) return text
        val codePoints = text.codePoints().toArray()
        val ranges = secretNodes
            .map { it.startMark.index until it.endMark.index }
            .filter { !it.isEmpty() }
            .sortedBy { it.first }
        val out = StringBuilder()
        var cursor = 0
        for (range in ranges) {
            if (range.first < cursor) continue
            out.appendCodePoints(codePoints, cursor, range.first)
            out.append(MASK)
            // Keep every line break of a multi-line value, so line numbers stay valid.
            repeat((range.first..range.last).count { codePoints[it] == '\n'.code }) { out.append('\n') }
            cursor = range.last + 1
        }
        out.appendCodePoints(codePoints, cursor, codePoints.size)
        return out.toString()
    }

    private fun StringBuilder.appendCodePoints(codePoints: IntArray, from: Int, to: Int) {
        for (i in from until to) appendCodePoint(codePoints[i])
    }
}

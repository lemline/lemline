// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.nodes

import com.fasterxml.jackson.core.JsonPointer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lemline.common.json.LemlineJson
import com.lemline.core.load
import com.lemline.core.workflows.WorkflowCache
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class JsonPointerValidationTest : FunSpec({

    val objectMapper = ObjectMapper()

    context("RFC 6901 JSON Pointer compliance") {

        test("do-nested.yaml - all node positions are valid JSON Pointers") {
            val (rootNode, workflowJson) = loadWorkflowWithJson("/examples/do-nested.yaml")
            val jacksonNode = workflowJson.toJacksonNode(objectMapper)

            val allNodes = collectAllNodes(rootNode)
            allNodes.forEach { node ->
                val position = node.position.toString()
                if (position != "/") {
                    val element = jacksonNode.at(JsonPointer.compile(position))
                    element.isMissingNode shouldBe false
                }
            }
        }

        test("fork.yaml - fork branches use correct JSON Pointer format") {
            val (rootNode, workflowJson) = loadWorkflowWithJson("/examples/fork.yaml")
            val jacksonNode = workflowJson.toJacksonNode(objectMapper)

            val allNodes = collectAllNodes(rootNode)
            val forkBranchNodes = allNodes.filter { it.position.toString().contains("/fork/branches/") }

            forkBranchNodes.size shouldBe 2

            forkBranchNodes.forEach { node ->
                val position = node.position.toString()
                position shouldStartWith "/do/0/raiseAlarm/fork/branches/"

                val element = jacksonNode.at(JsonPointer.compile(position))
                element.isMissingNode shouldBe false
            }
        }

        test("try-catch.yaml - try and catch blocks use correct JSON Pointer format") {
            val (rootNode, workflowJson) = loadWorkflowWithJson("/examples/try-catch.yaml")
            val jacksonNode = workflowJson.toJacksonNode(objectMapper)

            val allNodes = collectAllNodes(rootNode)

            val tryNode = allNodes.find { it.position.toString().endsWith("/try") }
            tryNode.shouldNotBeNull()
            tryNode.position.toString() shouldBe "/do/0/tryGetPet/try"

            val element = jacksonNode.at(JsonPointer.compile(tryNode.position.toString()))
            element.isMissingNode shouldBe false
        }

        test("for.yaml - for loop body uses correct JSON Pointer format") {
            val (rootNode, workflowJson) = loadWorkflowWithJson("/examples/for.yaml")
            val jacksonNode = workflowJson.toJacksonNode(objectMapper)

            val allNodes = collectAllNodes(rootNode)

            val forDoNodes = allNodes.filter {
                it.position.toString().contains("/do") && it.position.toString().count { c -> c == '/' } > 3
            }

            forDoNodes.forEach { node ->
                val position = node.position.toString()
                val element = jacksonNode.at(JsonPointer.compile(position))
                element.isMissingNode shouldBe false
            }
        }

        test("do-single.yaml - simple workflow positions are valid") {
            val (rootNode, workflowJson) = loadWorkflowWithJson("/examples/do-single.yaml")
            val jacksonNode = workflowJson.toJacksonNode(objectMapper)

            val allNodes = collectAllNodes(rootNode)

            val taskNodes = allNodes.filter { it.position.toString().matches(Regex("/do/\\d+/\\w+")) }
            taskNodes.size shouldBe 1

            val firstTask = taskNodes.first()
            firstTask.position.toString() shouldBe "/do/0/getPet"

            val element = jacksonNode.at(JsonPointer.compile(firstTask.position.toString()))
            element.isMissingNode shouldBe false
        }

        test("do-multiple.yaml - multiple tasks have sequential indices") {
            val (rootNode, _) = loadWorkflowWithJson("/examples/do-multiple.yaml")

            val allNodes = collectAllNodes(rootNode)

            val topLevelTasks = allNodes.filter {
                it.position.toString().matches(Regex("/do/\\d+/\\w+"))
            }.sortedBy { it.position.toString() }

            topLevelTasks.forEachIndexed { index, node ->
                val expectedPrefix = "/do/$index/"
                node.position.toString() shouldStartWith expectedPrefix
            }
        }

        test("all example workflows - positions follow RFC 6901 format") {
            val exampleFiles = listOf(
                "/examples/do-single.yaml",
                "/examples/do-nested.yaml",
                "/examples/do-multiple.yaml",
                "/examples/fork.yaml",
                "/examples/try-catch.yaml",
                "/examples/for.yaml",
                "/examples/set.yaml",
            )

            exampleFiles.forEach { file ->
                val (rootNode, workflowJson) = loadWorkflowWithJson(file)
                val jacksonNode = workflowJson.toJacksonNode(objectMapper)

                val allNodes = collectAllNodes(rootNode)
                allNodes.forEach { node ->
                    val position = node.position.toString()

                    position shouldStartWith "/"

                    if (position != "/") {
                        val element = jacksonNode.at(JsonPointer.compile(position))
                        element.isMissingNode shouldBe false
                    }
                }
            }
        }
    }
})

private fun loadWorkflowWithJson(resourcePath: String): Pair<Node<RootTask>, JsonObject> {
    val yamlContent = load(resourcePath)
    val workflow = WorkflowCache.parseYamlAndPut(yamlContent)
    val rootNode = WorkflowCache.getRootNode(workflow)
    val workflowJson = LemlineJson.encodeToElement(workflow).jsonObject
    return rootNode to workflowJson
}

private fun collectAllNodes(rootNode: Node<*>): List<Node<*>> {
    val nodes = mutableListOf<Node<*>>()

    fun traverse(node: Node<*>) {
        nodes.add(node)
        node.children?.forEach { traverse(it) }
    }

    traverse(rootNode)
    return nodes
}

private fun JsonObject.toJacksonNode(objectMapper: ObjectMapper): JsonNode {
    val jsonString = LemlineJson.encodeToString(this)
    return objectMapper.readTree(jsonString)
}

// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.inMemory

import com.lemline.core.testcases.impl.TestMocks.reading1Event
import com.lemline.core.testcases.impl.TestMocks.reading2Event
import com.lemline.core.testcases.impl.TestMocks.reading3ThresholdEvent
import com.lemline.core.testcases.impl.WorkflowTestCase
import com.lemline.core.testcases.impl.WorkflowTestValidators.expectOutput
import com.lemline.runner.tests.profiles.InMemoryProfile
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import kotlin.time.ExperimentalTime
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)
@QuarkusTest
@TestProfile(InMemoryProfile::class)
internal class DebugListenTest : InMemoryWorkflowTest(
    listOf(
        WorkflowTestCase(
            name = "listen foreach processes events sequentially with delay preserving order",
            cloudEvents = listOf(
                reading1Event,
                reading2Event,
                reading3ThresholdEvent
            ),
            yaml = $$"""
                do:
                  - setContext:
                      set:
                        value: 0
                      export:
                        as: ${ . }
                  - collectReadings:
                      listen:
                        to:
                          any:
                            - with:
                                type: sensor.reading
                          until: . | any(.value > 100)
                      foreach:
                        do:
                          - captureContext:
                              set:
                                value: ${ $context.value }
                          - simulateSlowProcessing:
                              wait:
                                milliseconds: 400
                          - returnResult:
                              set:
                                value: ${ .value + 1 }
                              export:
                                as: ${ . }
            """.trimIndent(),
            tags = setOf("listen", "foreach", "sequential"),
            validate = expectOutput(
                JsonArray(
                    listOf(
                        buildJsonObject { put("value", 1) },
                        buildJsonObject { put("value", 2) },
                        buildJsonObject { put("value", 3) }
                    ))
            )
        ),
    )
)

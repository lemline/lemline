// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.testcases.rabbitmq

import com.lemline.runner.testcases.bases.BrokerScheduleExecutionTest
import com.lemline.runner.common.test.RequiresDocker
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile

@RequiresDocker
@QuarkusTest
@TestProfile(RabbitMQScheduleExecutionProfile::class)
internal class RabbitMQScheduleExecutionTest : BrokerScheduleExecutionTest()

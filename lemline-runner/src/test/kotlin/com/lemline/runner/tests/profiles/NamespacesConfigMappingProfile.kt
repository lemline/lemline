// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.tests.profiles

import com.lemline.runner.common.config.ANALYTICS_TYPE_H2
import com.lemline.runner.common.config.DatabaseType
import com.lemline.runner.common.config.LEMLINE_ANALYTICS_TYPE
import com.lemline.runner.common.config.LEMLINE_DATABASE_TYPE
import com.lemline.runner.common.config.LEMLINE_MESSAGING_PREFIX
import com.lemline.runner.common.config.LEMLINE_MESSAGING_TYPE
import com.lemline.runner.common.config.LEMLINE_NAMESPACES
import com.lemline.runner.common.config.MessagingType
import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Profile validating config mapping of `lemline.namespaces` and `lemline.messaging.prefix`.
 * The namespace set deliberately contains a dash-prefix pair (acme / acme-prod) to exercise
 * the startup WARN path without failing the boot.
 */
class NamespacesConfigMappingProfile : QuarkusTestProfile {

    override fun getConfigOverrides(): Map<String, String> =
        mapOf(
            LEMLINE_DATABASE_TYPE to DatabaseType.H2.configValue,
            LEMLINE_MESSAGING_TYPE to MessagingType.IN_MEMORY.configValue,
            LEMLINE_ANALYTICS_TYPE to ANALYTICS_TYPE_H2,

            LEMLINE_NAMESPACES to "acme,acme-prod",
            LEMLINE_MESSAGING_PREFIX to "lem",
        )
}

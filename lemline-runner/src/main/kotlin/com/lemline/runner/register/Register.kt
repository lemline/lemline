// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.register

import io.agroal.api.AgroalDataSource
import io.quarkus.runtime.annotations.RegisterForReflection
import io.serverlessworkflow.api.types.Workflow
import io.serverlessworkflow.impl.expressions.DateTimeDescriptor
import org.eclipse.microprofile.config.Config
import org.flywaydb.core.Flyway

/**
 * The @RegisterForReflection annotation is used to ensure that the specified classes and their hierarchies
 * are available for reflection at runtime. This is particularly important in environments like Quarkus,
 * which uses ahead-of-time (AOT) compilation to optimize applications for fast startup and low memory usage.
 * During AOT compilation, unused classes and reflection metadata are often removed to reduce the application size.
 *
 * Full hierarchy registration: The registerFullHierarchy = true ensures that not only the specified classes
 * but also their parent classes and interfaces are included for reflection. In this case, the listed classes
 * (e.g., AgroalDataSource, DataSource, XAConnection) are likely required for database connection pooling,
 * transaction management, or other JDBC-related operations. Without this annotation, the application might fail
 * to function correctly in a native image or optimized runtime.
 *
 */
@RegisterForReflection(
    targets = [Workflow::class, DateTimeDescriptor::class, Config::class, AgroalDataSource::class, Flyway::class],
    registerFullHierarchy = true
)
internal class Register

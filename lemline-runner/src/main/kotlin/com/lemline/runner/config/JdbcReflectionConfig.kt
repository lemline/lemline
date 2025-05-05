package com.lemline.runner.config

import com.arjuna.ats.internal.jdbc.ConnectionManager
import io.agroal.api.AgroalDataSource
import io.quarkus.runtime.annotations.RegisterForReflection
import javax.naming.Reference
import javax.sql.ConnectionEventListener
import javax.sql.ConnectionPoolDataSource
import javax.sql.DataSource
import javax.sql.PooledConnection
import javax.sql.XAConnection
import javax.transaction.xa.XAResource
import javax.transaction.xa.Xid
import org.flywaydb.core.Flyway

/**
 * The @RegisterForReflection annotation is used to ensure that the specified classes and their hierarchies are available for reflection at runtime. This is particularly important in environments like Quarkus, which uses ahead-of-time (AOT) compilation to optimize applications for fast startup and low memory usage. During AOT compilation, unused classes and reflection metadata are often removed to reduce the application size.
 *
 * By explicitly registering these classes for reflection, you ensure that:
 *
 * Reflection-based frameworks: Classes required by frameworks (e.g., JDBC, transaction management)
 * that rely on reflection are not removed during the build process.
 *
 * Runtime compatibility: The application can dynamically access these classes and their hierarchies at runtime
 * without encountering ClassNotFoundException or similar issues.
 *
 * Full hierarchy registration: The registerFullHierarchy = true ensures that not only the specified classes
 * but also their parent classes and interfaces are included for reflection. In this case, the listed classes
 * (e.g., AgroalDataSource, DataSource, XAConnection) are likely required for database connection pooling,
 * transaction management, or other JDBC-related operations. Without this annotation, the application might fail
 * to function correctly in a native image or optimized runtime.
 *
 * See https://chatgpt.com/share/68185fe7-9aac-800a-baf7-c26b702c9c7b
 */
@RegisterForReflection(
    targets = [
        AgroalDataSource::class,
        DataSource::class,
        XAConnection::class,
        XAResource::class,
        ConnectionPoolDataSource::class,
        ConnectionEventListener::class,
        ConnectionManager::class,
        PooledConnection::class,
        Xid::class,
        Reference::class,
        Flyway::class,
    ],
    registerFullHierarchy = true
)
class JdbcReflectionConfig


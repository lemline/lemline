// SPDX-License-Identifier: BUSL-1.1
@file:OptIn(ExperimentalTime::class, ExperimentalSerializationApi::class)

package com.lemline.runner.repositories.bases

import com.lemline.common.values.IDV7
import com.lemline.runner.models.bases.CleanerColumns
import com.lemline.runner.models.bases.CleanerColumnsBase
import com.lemline.runner.models.bases.OptionalCleanerColumns
import com.lemline.runner.repositories.capabilities.BaseCleanerCapable
import com.lemline.runner.repositories.capabilities.CleanerCapabilities
import com.lemline.runner.repositories.capabilities.CleanerCapable
import com.lemline.runner.repositories.capabilities.ID_COLUMN
import com.lemline.runner.repositories.capabilities.IdCapabilities
import com.lemline.runner.repositories.capabilities.IdCapable
import com.lemline.runner.repositories.capabilities.OptionalCleanerCapable
import java.sql.Connection
import java.sql.ResultSet
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi

abstract class CleanerRepository<T : CleanerColumns> : CleanerRepositoryBase<T>() {
    override val idCapable by lazy { IdCapable(this) }
    override val cleanerCapable by lazy { CleanerCapable(this) }

    val ResultSet.runAt: Instant get() = with(cleanerCapable) { this@runAt.runAt }
}

abstract class OptionalCleanerRepository<T : OptionalCleanerColumns> : CleanerRepositoryBase<T>() {
    override val idCapable by lazy { IdCapable(this) }
    override val cleanerCapable by lazy { OptionalCleanerCapable(this) }

    val ResultSet.runAt: Instant? get() = with(cleanerCapable) { this@runAt.runAt }
}

abstract class CleanerRepositoryBase<T : CleanerColumnsBase> : Repository<T>(),
    IdCapabilities<T>, CleanerCapabilities<T> {

    abstract val idCapable: IdCapable<T>
    abstract val cleanerCapable: BaseCleanerCapable<T>

    // Key Columns
    override val keyColumns = listOf(ID_COLUMN)

    override val prepareStatementMap by lazy {
        idCapable.mapping + cleanerCapable.mapping
    }

    val ResultSet.id get() = with(idCapable) { this@id.id }
    val ResultSet.runStatus get() = with(cleanerCapable) { this@runStatus.runStatus }

    // ID Operations
    override suspend fun findById(id: IDV7, connection: Connection?): T? =
        idCapable.findById(id, connection)

    override suspend fun deleteById(id: IDV7, connection: Connection?) =
        idCapable.deleteById(id, connection)

    // Cleaner Operations
    override suspend fun findEntitiesToDelete(cutoffDate: Instant, limit: Int, connection: Connection?) =
        cleanerCapable.findEntitiesToDelete(cutoffDate, limit, connection)
}

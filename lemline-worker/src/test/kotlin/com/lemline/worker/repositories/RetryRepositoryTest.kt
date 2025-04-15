package com.lemline.worker.repositories

import com.lemline.worker.PostgresTestResource
import com.lemline.worker.models.RetryModel
import com.lemline.worker.outbox.OutBoxStatus
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import jakarta.transaction.UserTransaction
import org.junit.jupiter.api.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

@QuarkusTest
@QuarkusTestResource(PostgresTestResource::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("integration")
internal class RetryRepositoryTest {

    @Inject
    lateinit var repository: RetryRepository

    @Inject
    lateinit var entityManager: EntityManager

    @Inject
    lateinit var userTransaction: UserTransaction

    @BeforeEach
    @Transactional
    internal fun setupTest() {
        // Clear the database before each test
        entityManager.createQuery("DELETE FROM RetryModel").executeUpdate()
        entityManager.flush()
    }

    @Transactional
    internal fun createPendingMessage(count: Int, attemptCount: Int = 0, duration: Int = -1) {
        // Given
        val now = Instant.now()
        // Create test messages in a transaction
        repeat(count) { i ->
            val message = RetryModel().apply {
                message = "test$i"
                status = OutBoxStatus.PENDING
                delayedUntil = now.plus(((1 + i) * duration).minutes.toJavaDuration())
                this.attemptCount = attemptCount
            }
            entityManager.persist(message)
        }
        entityManager.flush()
    }

    @Transactional
    internal fun createSentMessage(count: Int, attemptCount: Int = 0, duration: Int = -2) {
        // Given
        val now = Instant.now()
        // Create test messages in a transaction
        repeat(count) { i ->
            val message = RetryModel().apply {
                message = "test$i"
                status = OutBoxStatus.SENT
                delayedUntil = now.plus(((1 + i) * duration).days.toJavaDuration())
                this.attemptCount = attemptCount
            }
            entityManager.persist(message)
        }
        entityManager.flush()
    }

    private fun findAndLockReadyToProcess(limit: Int, maxAttempts: Int) =
        repository.findAndLockReadyToProcess(limit = limit, maxAttempts = maxAttempts)

    @Transactional
    internal fun findAndProcess(limit: Int, maxAttempts: Int) =
        findAndLockReadyToProcess(limit, maxAttempts)
            .onEach { it.status = OutBoxStatus.SENT }

    private fun findAndLockForDeletion(cutoffDate: Instant, limit: Int) =
        repository.findAndLockForDeletion(cutoffDate, limit)

    @Transactional
    internal fun findAndDelete(cutoffDate: Instant, limit: Int) =
        findAndLockForDeletion(cutoffDate, limit)
            .onEach { it.delete() }

    @Test
    @Transactional
    fun `saveMessage should create a new delayed message with correct properties`() {
        // Given
        val message = "test message"
        val delayedUntil = Instant.now().plus(5, ChronoUnit.MINUTES)

        // When
        repository.save(RetryModel.create(message, delayedUntil))

        // Then
        val savedMessage = entityManager
            .createQuery("FROM RetryModel", RetryModel::class.java)
            .singleResult

        Assertions.assertNotNull(savedMessage)
        Assertions.assertEquals(message, savedMessage.message)
        Assertions.assertEquals(delayedUntil, savedMessage.delayedUntil)
        Assertions.assertEquals(OutBoxStatus.PENDING, savedMessage.status)
        Assertions.assertEquals(0, savedMessage.attemptCount)
    }

    @Test
    @Transactional
    fun `saveMessage should allow multiple messages with same content but different delays`() {
        // Given
        val message = "test message"
        val firstDelay = Instant.now().plus(5, ChronoUnit.MINUTES)
        val secondDelay = Instant.now().plus(10, ChronoUnit.MINUTES)

        // When
        repository.save(RetryModel.create(message, firstDelay))
        repository.save(RetryModel.create(message, secondDelay))

        // Then
        val savedMessages = entityManager
            .createQuery("FROM RetryModel", RetryModel::class.java)
            .resultList

        Assertions.assertEquals(2, savedMessages.size)

        // Verify first message
        Assertions.assertEquals(message, savedMessages[0].message)
        Assertions.assertEquals(firstDelay, savedMessages[0].delayedUntil)
        Assertions.assertEquals(OutBoxStatus.PENDING, savedMessages[0].status)
        Assertions.assertEquals(0, savedMessages[0].attemptCount)

        // Verify second message
        Assertions.assertEquals(message, savedMessages[1].message)
        Assertions.assertEquals(secondDelay, savedMessages[1].delayedUntil)
        Assertions.assertEquals(OutBoxStatus.PENDING, savedMessages[1].status)
        Assertions.assertEquals(0, savedMessages[1].attemptCount)
    }

    @Test
    internal fun `findAndLockReadyToProcess should return messages ready to process`() {
        // Given
        createPendingMessage(2)

        // When
        val result = findAndLockReadyToProcess(limit = 10, maxAttempts = 3)

        // Then
        Assertions.assertEquals(2, result.size)
        Assertions.assertTrue(result.all { it.status == OutBoxStatus.PENDING })
        Assertions.assertTrue(result.all { it.delayedUntil <= Instant.now() })
        Assertions.assertTrue(result.all { it.attemptCount < 3 })
    }

    @Test
    internal fun `findAndLockReadyToProcess should not return messages with too many attempts`() {
        // Given
        createPendingMessage(2, 3)

        // When
        val result = findAndLockReadyToProcess(limit = 10, maxAttempts = 3)

        // Then
        Assertions.assertTrue(result.isEmpty())
    }

    @Test
    internal fun `findAndLockReadyToProcess should not return future messages`() {
        // Given
        createPendingMessage(2, duration = 1)

        // When
        val result = findAndLockReadyToProcess(limit = 10, maxAttempts = 3)

        // Then
        Assertions.assertTrue(result.isEmpty())
    }

    @Test
    internal fun `findAndLockReadyToProcess should respect limit parameter`() {
        // Given
        createPendingMessage(5)

        // When
        val result = findAndLockReadyToProcess(limit = 2, maxAttempts = 3)

        // Then
        Assertions.assertEquals(2, result.size)
    }

    @Test
    internal fun `findAndLockForDeletion should return sent messages older than cutoff`() {
        // Given
        createSentMessage(1)

        // When
        val cutoffDate = Instant.now().minus(1, ChronoUnit.DAYS)
        val result = findAndLockForDeletion(cutoffDate, limit = 10)

        // Then
        Assertions.assertEquals(1, result.size)
        Assertions.assertEquals(OutBoxStatus.SENT, result[0].status)
        Assertions.assertTrue(result[0].delayedUntil < cutoffDate)
    }

    @Test
    internal fun `findAndLockForDeletion should not return messages newer than cutoff`() {
        // Given
        createSentMessage(1, duration = 0)

        // When
        val cutoffDate = Instant.now().minus(1, ChronoUnit.DAYS)
        val result = findAndLockForDeletion(cutoffDate, limit = 10)

        // Then
        Assertions.assertTrue(result.isEmpty())
    }

    @Test
    fun `findAndLockForDeletion should not return non-sent messages`() {
        // Given
        createPendingMessage(1, duration = -2 * 24 * 60)

        // When
        val cutoffDate = Instant.now().minus(1, ChronoUnit.DAYS)
        val result = findAndLockForDeletion(cutoffDate, limit = 10)

        // Then
        Assertions.assertTrue(result.isEmpty())
    }

    @Test
    fun `findAndLockForDeletion should respect limit parameter`() {
        // Given
        createSentMessage(5)

        // When
        val cutoffDate = Instant.now().minus(1, ChronoUnit.DAYS)
        val result = findAndLockForDeletion(cutoffDate, limit = 2)

        // Then
        Assertions.assertEquals(2, result.size)
    }

    @Test
    fun `findAndLockReadyToProcess should not return same messages to concurrent requests`() {
        // Given
        val messageCount = 10
        val concurrentRequests = 5
        val limit = 3

        // Create test messages in a transaction
        createPendingMessage(messageCount)

        // When
        val results = mutableListOf<List<RetryModel>>()
        val latch = CountDownLatch(concurrentRequests)
        val executor = Executors.newFixedThreadPool(concurrentRequests)

        // Submit concurrent requests
        repeat(concurrentRequests) {
            executor.submit {
                try {
                    val result = findAndProcess(limit = limit, maxAttempts = 3)
                    synchronized(results) { results.add(result) }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Wait for all requests to complete
        latch.await(2, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)

        // Then
        // Verify total number of messages processed
        val totalProcessed = results.sumOf { it.size }
        Assertions.assertEquals(messageCount, totalProcessed, "All messages should be processed")

        // Verify no duplicate messages
        val allMessages = results.flatten()
        val uniqueMessages = allMessages.distinctBy { it.id }
        Assertions.assertEquals(allMessages.size, uniqueMessages.size, "No duplicate messages should be returned")
    }

    @Test
    fun `findAndLockForDeletion should handle concurrent deletion requests`() {
        // Given
        val messageCount = 10
        val concurrentRequests = 5
        val limit = 3

        // Create test messages
        createSentMessage(messageCount)

        // When
        val results = mutableListOf<List<RetryModel>>()
        val latch = CountDownLatch(concurrentRequests)
        val executor = Executors.newFixedThreadPool(concurrentRequests)

        // Submit concurrent requests
        repeat(concurrentRequests) {
            executor.submit {
                try {
                    val cutoffDate = Instant.now().minus(1L, ChronoUnit.DAYS)
                    val result = findAndDelete(cutoffDate, limit = limit)
                    synchronized(results) { results.add(result) }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Wait for all requests to complete
        latch.await(2, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)

        // Then
        // Verify total number of messages processed
        val totalProcessed = results.sumOf { it.size }
        Assertions.assertEquals(messageCount, totalProcessed, "All messages should be processed")

        // Verify no duplicate messages
        val allMessages = results.flatten()
        val uniqueMessages = allMessages.distinctBy { it.id }
        Assertions.assertEquals(allMessages.size, uniqueMessages.size, "No duplicate messages should be returned")
    }

    @Test
    fun `should handle mixed concurrent operations`() {
        // Given
        val now = Instant.now()
        val cutoffDate = now.minus(1L, ChronoUnit.DAYS)
        val messageCount = 20
        val concurrentRequests = 10
        val limit = 3

        // Create test messages
        userTransaction.begin()
        repeat(messageCount) { i ->
            val message = RetryModel().apply {
                message = "test$i"
                status = if (i < messageCount / 2) OutBoxStatus.PENDING else OutBoxStatus.SENT
                delayedUntil = if (i < messageCount / 2) {
                    now.minus((i + 1).toLong(), ChronoUnit.MINUTES)
                } else {
                    now.minus(2L, ChronoUnit.DAYS)
                }
                attemptCount = 0
            }
            entityManager.persist(message)
        }
        entityManager.flush()
        userTransaction.commit()

        // When
        val processResults = mutableListOf<List<RetryModel>>()
        val deleteResults = mutableListOf<List<RetryModel>>()
        val latch = CountDownLatch(concurrentRequests)
        val executor = Executors.newFixedThreadPool(concurrentRequests)

        // Submit concurrent requests
        repeat(concurrentRequests) { index ->
            executor.submit {
                try {
                    if (index % 2 == 0) {
                        val result = findAndProcess(limit = limit, maxAttempts = 3)
                        synchronized(processResults) { processResults.add(result) }
                    } else {
                        val result = findAndDelete(cutoffDate, limit = limit)
                        synchronized(deleteResults) { deleteResults.add(result) }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Wait for all requests to complete
        latch.await(2, TimeUnit.SECONDS)
        executor.shutdown()
        executor.awaitTermination(2, TimeUnit.SECONDS)

        // Then
        // Verify total number of messages processed
        val totalProcessed = processResults.sumOf { it.size }
        val totalDeleted = deleteResults.sumOf { it.size }
        Assertions.assertEquals(messageCount / 2, totalProcessed, "All pending messages should be processed")
        Assertions.assertEquals(messageCount / 2, totalDeleted, "All sent messages should be deleted")

        // Verify no duplicate messages
        val allProcessedMessages = processResults.flatten()
        val allDeletedMessages = deleteResults.flatten()
        val uniqueProcessedMessages = allProcessedMessages.distinctBy { it.id }
        val uniqueDeletedMessages = allDeletedMessages.distinctBy { it.id }

        Assertions.assertEquals(
            allProcessedMessages.size,
            uniqueProcessedMessages.size,
            "No duplicate messages in processing"
        )
        Assertions.assertEquals(
            allDeletedMessages.size,
            uniqueDeletedMessages.size,
            "No duplicate messages in deletion"
        )

        // Verify no overlap between processed and deleted messages
        val processedIds = allProcessedMessages.map { it.id }.toSet()
        val deletedIds = allDeletedMessages.map { it.id }.toSet()
        Assertions.assertTrue(
            processedIds.intersect(deletedIds).isEmpty(),
            "No message should be both processed and deleted"
        )
    }
} 
// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.ids

import com.github.f4b6a3.uuid.UuidCreator
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.*

/**
 * Utility class for generating IDs used throughout the application.
 * Centralizes ID generation logic to ensure consistency.
 */
object IdGenerator {
    /**
     * Generates a time-ordered UUID string.
     * Uses UuidCreator to create a time-ordered UUID which provides both uniqueness
     * and chronological ordering.
     */
    fun generateUUIDV7(): UUID {
        return UuidCreator.getTimeOrderedEpoch()
    }

    /**
     * Derive a new UUIDv7 deterministically from an existing UUIDv7.
     *
     * - Keeps the original v7 48-bit timestamp -> remains time-orderable like the source.
     * - Sets version=7 and RFC-4122 variant.
     * - Random bits are deterministically derived from SHA-256(salt || sourceV7 bytes),
     *   making the mapping idempotent for the same input and salt.
     *
     * Use a non-empty salt to create a separate derivation namespace (recommended).
     */
    fun deterministicUUIDV7(sourceV7: UUID, salt: String = ""): UUID {
        require(sourceV7.version() == 7) { "sourceV7 must be a UUID version 7" }
        // A UUIDv7 must use the IETF RFC 4122 variant, which is variant 2.
        require(sourceV7.variant() == 2) { "sourceV7 must have the RFC 4122 variant (2)" }

        // --- Extract the 48-bit timestamp directly from the MSB (bits 63..16) ---
        // Using an unsigned right shift keeps zero-fill on the left.
        val ts = sourceV7.mostSignificantBits ushr 16

        // --- Deterministic entropy: SHA-256( salt || sourceV7 bytes ) -> take 80 bits ---
        // We need 74 random bits total (12 for rand_a, 62 for rand_b).
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt.toByteArray())
        // Update the digest with the UUID bytes using a ByteBuffer to avoid an intermediate array copy.
        md.update(
            ByteBuffer.allocate(16)
                .putLong(sourceV7.mostSignificantBits)
                .putLong(sourceV7.leastSignificantBits)
                .flip() // Prepare buffer for reading by the digest.
        )
        val digest = md.digest() // 32-byte hash; we will consume the first 10 bytes (80 bits).

        // --- Split the digest into rand_a (12 bits) and rand_b (62 bits) per UUIDv7 layout ---
        // rand_a: high 12 bits from digest[0..1]
        val randA = ((digest[0].toInt() and 0xFF) shl 4) or ((digest[1].toInt() and 0xFF) ushr 4)

        // rand_b: remaining 62 bits = low 4 bits of digest[1] + digest[2..8] + top 2 bits of digest[9]
        var randB = (digest[1].toLong() and 0x0F) // 4 bits
        for (i in 2..8) {                         // + 7*8 = 56 bits
            randB = (randB shl 8) or (digest[i].toLong() and 0xFF)
        }
        randB = (randB shl 2) or ((digest[9].toLong() and 0xFF) ushr 6) // + 2 bits = 62 total

        // --- Build MSB: | 48-bit timestamp | 4-bit version (7) | 12-bit rand_a |
        val msb = (ts shl 16) or 0x7000L or (randA.toLong() and 0x0FFFL)

        // --- Build LSB: | 2-bit variant ('10') | 62-bit rand_b |
        // Top two bits set to 10b (RFC-4122); lower 62 bits are rand_b.
        val lsb = (2L shl 62) or (randB and 0x3FFF_FFFF_FFFF_FFFFL)

        return UUID(msb, lsb)
    }

// ------- Example -------
// val source = UUID.fromString("018fa0d6-9ec0-7c5e-8a64-9a3c2d8e7b4c")
// val derived = deriveUuidV7FromV7(source, UUID.fromString("2c8e2d0c-9a53-4a30-8e6b-6b7b5ec7a1fb"))
// println(derived)

}


fun main() {
    val id = IdGenerator.generateUUIDV7()
    println(id)
    println(IdGenerator.deterministicUUIDV7(id, "test"))
    println(IdGenerator.deterministicUUIDV7(id, "test"))
    println(IdGenerator.deterministicUUIDV7(id))
    println(IdGenerator.deterministicUUIDV7(id))
}

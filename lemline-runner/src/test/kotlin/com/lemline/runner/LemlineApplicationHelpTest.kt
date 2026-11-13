// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LemlineApplicationHelpTest {
    @Test
    fun `nested help is displayed without starting Quarkus`() {
        val output = captureStdout {
            LemlineApplication.main(arrayOf("instance", "start", "--help"))
        }

        assertTrue(output.contains("Usage: lemline instance start"), output)
    }

    @Test
    fun `version is displayed without starting Quarkus`() {
        val output = captureStdout {
            LemlineApplication.main(arrayOf("--version"))
        }

        assertTrue(output.contains("Lemline Runner"), output)
    }

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val output = ByteArrayOutputStream()
        try {
            System.setOut(PrintStream(output))
            block()
        } finally {
            System.setOut(original)
        }
        return output.toString()
    }
}

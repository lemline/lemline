// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities.runs

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

fun main() {
    println("Testing ShellRun command parsing...\n")
    
    // Test 1: Basic command with quoted arguments
    println("Test 1: echo \"Hello World\"")
    val test1 = ShellRun("echo \"Hello World\"")
    println("  Parsed command: ${test1.command}")
    println("  Executing...")
    val result1 = test1.execute()
    println("  Exit code: ${result1.code}")
    println("  Stdout: '${result1.stdout}'")
    println("  Stderr: '${result1.stderr}'\n")
    
    // Test 2: Command with multiple quoted arguments
    println("Test 2: echo \"Hello\" \"World\" \"with spaces\"")
    val test2 = ShellRun("echo \"Hello\" \"World\" \"with spaces\"")
    println("  Parsed command: ${test2.command}")
    println("  Executing...")
    val result2 = test2.execute()
    println("  Exit code: ${result2.code}")
    println("  Stdout: '${result2.stdout}'")
    println("  Stderr: '${result2.stderr}'\n")
    
    // Test 3: Command with mixed quoted and unquoted arguments
    println("Test 3: echo Hello \"beautiful World\"!")
    val test3 = ShellRun("echo Hello \"beautiful World\"!")
    println("  Parsed command: ${test3.command}")
    println("  Executing...")
    val result3 = test3.execute()
    println("  Exit code: ${result3.code}")
    println("  Stdout: '${result3.stdout}'")
    println("  Stderr: '${result3.stderr}'")
}

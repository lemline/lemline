# Test Conversion Status

The following base test files still need to be created by converting from JUnit to Kotest:

- [ ] TryTaskExecutionTest.kt (25+ tests)
- [ ] RunShellExecutionTest.kt (18+ tests)
- [ ] CallHttpExecutionTest.kt (14+ tests)
- [ ] RunScriptExecutionTest.kt (22+ tests)
- [ ] RunWorkflowExecutionTest.kt (13+ tests)
- [ ] WaitExecutionTest.kt (12+ tests)

## Conversion Steps for Each File:

1. Change package to `com.lemline.core.execution.bases`
2. Change class to `abstract class XxxTest : FunSpec({})`
3. Convert `@Test fun testName() = runTest {` to `test("test name") {`
4. Replace `assertEquals(expected, actual)` with `actual shouldBe expected`
5. Replace `assertFailsWith<Exception>` with `shouldThrow<Exception>`
6. Replace `assertTrue(condition)` with `condition shouldBe true`
7. Replace `assertNotNull(value)` with `value.shouldNotBeNull()`
8. Replace `CompleteOrchestrator.run(rootNode, input)` with `executeWorkflow(yaml, input)`
9. Remove `val rootNode = getWorkflowNode(yaml)` lines
10. Add abstract method at end:
```kotlin
protected abstract suspend fun executeWorkflow(
    yaml: String,
    input: JsonElement,
    namespace: String = "default",
    name: String = "test",
    version: String = "0.1.0"
): JsonElement
```

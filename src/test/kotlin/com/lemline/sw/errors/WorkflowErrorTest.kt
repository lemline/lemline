package com.lemline.sw.errors

import com.fasterxml.jackson.databind.ObjectMapper

class WorkflowErrorTest {
    private val mapper = ObjectMapper()

//    @Test
//    fun `test error serialization to JSON`() {
//        val error = WorkflowError(
//            errorType = VALIDATION,
//            title = "Invalid_input",
//            status = 400,
//            details = "The_input_data_does_not_match_the_required_schema",
//            position = JsonPointer("/do/0").toPosition()
//        )
//
//        val json = mapper.writeValueAsString(error)
//        val expected = """
//            {
//                "type":"https://serverlessworkflow.io/spec/1.0.0/errors/validation",
//                "status":400,
//                "position":"/do/0",
//                "title":"Invalid_input",
//                "details":"The_input_data_does_not_match_the_required_schema"
//            }
//        """.trimIndent().replace("\\s".toRegex(), "")
//
//        assertEquals(expected, json)
//    }
//
//    @Test
//    fun `test error deserialization from JSON`() {
//        val json = """
//            {
//                "type":"https://serverlessworkflow.io/spec/1.0.0/errors/timeout",
//                "status":408,
//                "position":"/do/1/try",
//                "title":"Operation timed out",
//                "details":"The operation exceeded the maximum allowed time"
//            }
//        """.trimIndent()
//
//        val error = mapper.readValue(json, WorkflowError::class.java)
//
//        assertEquals("https://serverlessworkflow.io/spec/1.0.0/errors/timeout", error.type)
//        assertEquals("Operation timed out", error.title)
//        assertEquals(408, error.status)
//        assertEquals("The operation exceeded the maximum allowed time", error.details)
//        assertEquals("/do/1/try", error.instance)
//    }
//
//    @Test
//    fun `test error with default status serialization`() {
//        val error = WorkflowError(
//            errorType = RUNTIME,
//            title = "Internal_error",
//            details = "An_unexpected_error_occurred",
//            position = JsonPointer("/do/2").toPosition(),
//            status = 455
//        )
//
//        val json = mapper.writeValueAsString(error)
//        val expected = """
//            {
//                "type":"https://serverlessworkflow.io/spec/1.0.0/errors/runtime",
//                "status":455,
//                "instance":"/do/2",
//                "title":"Internal_error",
//                "details":"An_unexpected_error_occurred"
//            }
//        """.trimIndent().replace("\\s".toRegex(), "")
//
//        assertEquals(expected, json)
//    }

}
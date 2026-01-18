// SPDX-License-Identifier: BUSL-1.1
package com.lemline.common.values

enum class Token(val token: String) {
    DO("do"),
    FOR("for"),
    FOREACH("foreach"),
    FORK("fork"),
    BRANCHES("branches"),
    WITH("with"),
    SUBSCRIPTION("subscription"),
    LISTEN("listen"),
    RAISE("raise"),
    RUN("run"),
    SET("set"),
    SWITCH("switch"),
    TRY("try"),
    CATCH("catch"),
    WAIT("wait"),
    CALL("call"),
    EMIT("emit"),
    FUN("_fn"),  // Function body marker for inline function execution
    ;

    override fun toString() = token
}

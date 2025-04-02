package com.lemline.sw.nodes

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
    EMIT("emit");

    override fun toString() = token
}
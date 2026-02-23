// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.auth

import com.lemline.runner.config.shared.LemlineConfiguration
import com.lemline.runner.config.shared.*
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.quarkus.grpc.GlobalInterceptor
import io.smallrye.jwt.auth.principal.JWTParser
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject

@GlobalInterceptor
@ApplicationScoped
class GatewayAuthInterceptor(
    private val config: LemlineConfiguration,
) : ServerInterceptor {

    @Inject
    lateinit var jwtParser: JWTParser

    @Inject
    lateinit var authorizer: GatewayAuthorizer

    override fun <ReqT : Any?, RespT : Any?> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        if (!config.gatewayAuthenticationEnabled) {
            return next.startCall(call, headers)
        }

        val authorizationHeader = headers.get(AUTHORIZATION_METADATA_KEY)
            ?: return closeUnauthenticated(call, "Missing Authorization metadata header")

        if (!authorizationHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            return closeUnauthenticated(call, "Authorization header must use Bearer scheme")
        }

        val token = authorizationHeader.substring(BEARER_PREFIX.length).trim()
        if (token.isEmpty()) {
            return closeUnauthenticated(call, "Bearer token is empty")
        }

        val jwt = try {
            jwtParser.parse(token)
        } catch (e: Exception) {
            return closeUnauthenticated(call, "Invalid JWT token: ${e.message}")
        }

        val principal = authorizer.principalFrom(jwt)
        val context = GatewayAuthContext.set(principal)
        return Contexts.interceptCall(context, call, headers, next)
    }

    private fun <ReqT : Any?, RespT : Any?> closeUnauthenticated(
        call: ServerCall<ReqT, RespT>,
        message: String
    ): ServerCall.Listener<ReqT> {
        call.close(Status.UNAUTHENTICATED.withDescription(message), Metadata())
        return object : ServerCall.Listener<ReqT>() {}
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private val AUTHORIZATION_METADATA_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }
}

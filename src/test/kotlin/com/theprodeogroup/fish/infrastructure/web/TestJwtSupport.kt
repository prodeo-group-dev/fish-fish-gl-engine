package com.theprodeogroup.fish.infrastructure.web

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date

/**
 * Test-only JWT support (docs/DDD_Design.md Section 10.19) - a locally
 * generated RSA keypair, no real network JWKS endpoint needed. This is
 * exactly the seam [Application.fishModule] (not `productionModule`)
 * was designed for: the verifier is a constructor/function parameter,
 * so tests supply a static-key verifier built directly from
 * [Algorithm.RSA256] instead of [buildJwksVerifier]'s JWKS-backed one.
 */
object TestJwtSupport {
    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val publicKey = keyPair.public as RSAPublicKey
    private val privateKey = keyPair.private as RSAPrivateKey

    const val ISSUER = "https://test-issuer.example.com/"
    const val AUDIENCE = "fish-gl-engine-test"

    fun verifier(): JWTVerifier =
        JWT.require(Algorithm.RSA256(publicKey, null))
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .build()

    /** Signs a test JWT for [email], valid for one hour - the only claim any route in this codebase reads. */
    fun signToken(email: String): String =
        JWT.create()
            .withIssuer(ISSUER)
            .withAudience(AUDIENCE)
            .withClaim("email", email)
            .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000))
            .sign(Algorithm.RSA256(publicKey, privateKey))
}

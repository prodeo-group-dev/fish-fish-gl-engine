package com.theprodeogroup.fish.infrastructure.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /health` - deliberately unauthenticated (registered outside
 * [fishAuthenticated]), matching the standard container/load-balancer
 * health-check convention: an orchestrator (ECS/Fargate, an AWS ALB)
 * needs to reach this without a JWT to decide whether to route traffic
 * to this instance at all.
 */
fun Route.healthRoutes() {
    get("/health") {
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }
}

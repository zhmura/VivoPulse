package com.vivopulse.app.navigation

sealed class Route(val path: String) {
    object Capture : Route("capture")
    object Processing : Route("processing")
    object Result : Route("result")
    object Reactivity : Route("reactivity")
}

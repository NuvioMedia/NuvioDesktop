package com.nuvio.app

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

internal expect val isIos: Boolean
internal expect val isDesktop: Boolean
internal expect val isWindows: Boolean


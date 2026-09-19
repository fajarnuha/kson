package com.fajarnuha.kson.cli

import platform.Foundation.NSProcessInfo
import platform.posix.isatty

internal actual fun isTerminal(fd: Int): Boolean = isatty(fd) == 1

internal actual fun environment(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for ((k, v) in NSProcessInfo.processInfo.environment) {
        if (k is String && v is String) out[k] = v
    }
    return out
}

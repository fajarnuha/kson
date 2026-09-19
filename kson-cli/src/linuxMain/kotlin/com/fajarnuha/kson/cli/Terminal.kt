package com.fajarnuha.kson.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.toKString
import platform.posix.__environ
import platform.posix.isatty

internal actual fun isTerminal(fd: Int): Boolean = isatty(fd) == 1

@OptIn(ExperimentalForeignApi::class)
internal actual fun environment(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val env = __environ ?: return out
    var i = 0
    while (true) {
        val entry = env[i]?.toKString() ?: break
        val eq = entry.indexOf('=')
        if (eq > 0) out[entry.substring(0, eq)] = entry.substring(eq + 1)
        i++
    }
    return out
}

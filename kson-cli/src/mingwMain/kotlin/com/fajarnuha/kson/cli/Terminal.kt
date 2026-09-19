package com.fajarnuha.kson.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.get
import kotlinx.cinterop.toKString
import platform.posix._environ
import platform.windows.CP_UTF8
import platform.windows.DWORDVar
import platform.windows.ENABLE_VIRTUAL_TERMINAL_PROCESSING
import platform.windows.GetConsoleMode
import platform.windows.GetStdHandle
import platform.windows.STD_INPUT_HANDLE
import platform.windows.STD_OUTPUT_HANDLE
import platform.windows.SetConsoleMode
import platform.windows.SetConsoleOutputCP

/** On Windows a handle is a terminal when it is a console; stdout additionally needs VT processing for colors. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun isTerminal(fd: Int): Boolean = memScoped {
    val handle = GetStdHandle(if (fd == 0) STD_INPUT_HANDLE else STD_OUTPUT_HANDLE)
    val mode = alloc<DWORDVar>()
    if (GetConsoleMode(handle, mode.ptr) == 0) return@memScoped false
    if (fd == 0) return@memScoped true
    SetConsoleOutputCP(CP_UTF8.toUInt())
    SetConsoleMode(handle, mode.value or ENABLE_VIRTUAL_TERMINAL_PROCESSING.toUInt()) != 0
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun environment(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val env = _environ ?: return out
    var i = 0
    while (true) {
        val entry = env[i]?.toKString() ?: break
        val eq = entry.indexOf('=', startIndex = 1)
        if (eq > 0) out[entry.substring(0, eq)] = entry.substring(eq + 1)
        i++
    }
    return out
}

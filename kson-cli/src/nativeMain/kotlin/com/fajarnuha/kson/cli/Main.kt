package com.fajarnuha.kson.cli

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fread
import platform.posix.getenv
import platform.posix.stderr
import platform.posix.stdin
import platform.posix.stdout
import kotlin.system.exitProcess

/** Whether file descriptor [fd] (0 = stdin, 1 = stdout) is an interactive terminal that can render ANSI colors. */
internal expect fun isTerminal(fd: Int): Boolean

/** All environment variables, for `$ENV` / `env` in queries. */
internal expect fun environment(): Map<String, String>

/** Reads a whole stream as UTF-8. */
@OptIn(ExperimentalForeignApi::class)
private fun readAll(file: CPointer<FILE>?): String {
    val out = ArrayList<ByteArray>()
    var total = 0
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val read = buffer.usePinned { fread(it.addressOf(0), 1.convert(), buffer.size.convert(), file) }.toInt()
        if (read <= 0) break
        out += buffer.copyOf(read)
        total += read
    }
    val all = ByteArray(total)
    var offset = 0
    for (chunk in out) {
        chunk.copyInto(all, offset)
        offset += chunk.size
    }
    return all.decodeToString()
}

@OptIn(ExperimentalForeignApi::class)
private fun readFileText(path: String): String {
    val file = fopen(path, "rb") ?: throw UsageException("Cannot open file: $path")
    try {
        return readAll(file)
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    val io = CliIo(
        readFile = ::readFileText,
        readStdin = { readAll(stdin) },
        out = { fputs(it, stdout) },
        err = { msg ->
            fflush(stdout)
            fputs(msg + "\n", stderr)
        },
        stdoutIsTerminal = isTerminal(1),
        stdinIsTerminal = isTerminal(0),
        getenv = { getenv(it)?.toKString() },
        environment = ::environment,
    )
    val code = runCli(args.toList(), io)
    fflush(stdout)
    exitProcess(code)
}

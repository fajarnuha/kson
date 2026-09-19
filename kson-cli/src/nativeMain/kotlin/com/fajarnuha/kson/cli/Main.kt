package com.fajarnuha.kson.cli

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.fread
import platform.posix.stderr
import platform.posix.stdin
import platform.posix.stdout
import kotlin.system.exitProcess

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
        out = { println(it) },
        err = { msg ->
            fflush(stdout)
            fputs(msg + "\n", stderr)
        },
    )
    val code = runCli(args.toList(), io)
    fflush(stdout)
    exitProcess(code)
}

package com.dorapilot.assistant

import android.util.Log
import java.io.BufferedWriter
import java.io.InterruptedIOException
import java.io.IOException
import java.io.OutputStreamWriter

class ToyboxShellManager {
    private var shellProcess: Process? = null
    private var shellWriter: BufferedWriter? = null
    private var shellReaderThread: Thread? = null
    private val writeLock = Any()
    @Volatile
    private var activeEmitter: ((String, String) -> Unit)? = null

    fun bootstrap(emit: (String, String) -> Unit) {
        ensureShellRunning(emit)
        emit("shell", "Persistent Toybox shell ready at /system/bin/sh")
    }

    fun healthcheck(emit: (String, String) -> Unit) {
        ensureShellRunning(emit)
        exec("uname -a; id; pwd; echo SHELL_OK", emit)
    }

    fun exec(command: String, emit: (String, String) -> Unit) {
        ensureShellRunning(emit)
        synchronized(writeLock) {
            val writer = shellWriter ?: throw IllegalStateException("Shell writer is unavailable")
            val escapedCommand = command.replace("\n", " ")
            writer.write("$escapedCommand; echo __DORA_EXIT:$?")
            writer.newLine()
            writer.flush()
        }
        emit("request", command)
    }

    fun close() {
        synchronized(writeLock) {
            runCatching { shellWriter?.write("exit\n") }
            runCatching { shellWriter?.flush() }
            runCatching { shellWriter?.close() }
            shellWriter = null

            runCatching { shellProcess?.destroy() }
            shellProcess = null

            runCatching { shellReaderThread?.interrupt() }
            shellReaderThread = null
        }
    }

    private fun ensureShellRunning(emit: (String, String) -> Unit) {
        activeEmitter = emit
        if (shellProcess?.isAlive == true && shellWriter != null) return

        synchronized(writeLock) {
            if (shellProcess?.isAlive == true && shellWriter != null) return
            close()

            val process = ProcessBuilder("/system/bin/sh")
                .redirectErrorStream(true)
                .start()

            shellProcess = process
            shellWriter = BufferedWriter(OutputStreamWriter(process.outputStream))
            shellReaderThread = Thread(
                {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                val channel = if (line.startsWith("__DORA_EXIT:")) "shell" else "stdout"
                                activeEmitter?.invoke(channel, line)
                            }
                        }
                    } catch (_: InterruptedIOException) {
                        // Expected when close() interrupts the blocking read.
                    } catch (_: IOException) {
                        // Treat stream closure and process teardown as normal shutdown.
                    } catch (t: Throwable) {
                        Log.e(TAG, "Shell reader thread failed", t)
                    }
                },
                "dora-toybox-shell-reader"
            ).apply { start() }
            emit("shell", "Opened /system/bin/sh process")
        }
    }

    companion object {
        private const val TAG = "ToyboxShellManager"
    }
}

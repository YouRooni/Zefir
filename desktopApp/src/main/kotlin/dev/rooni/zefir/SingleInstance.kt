package dev.rooni.zefir

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock

object SingleInstance {
    private var lock: FileLock? = null

    fun acquire(home: File, args: Array<String>, onMessage: (String) -> Unit): Boolean {
        val lockFile = File(home, "instance.lock")
        val portFile = File(home, "instance.port")
        val channel = RandomAccessFile(lockFile, "rw").channel
        val taken = runCatching { channel.tryLock() }.getOrNull()
        if (taken == null) {
            channel.close()
            val port = runCatching { portFile.readText().trim().toInt() }.getOrNull() ?: return false
            runCatching {
                Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                    socket.soTimeout = 3000
                    socket.getOutputStream().bufferedWriter().apply {
                        write(SHOW)
                        newLine()
                        args.filter { it.isNotBlank() }.forEach { write(it); newLine() }
                        flush()
                    }
                }
            }
            return false
        }
        lock = taken
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        portFile.writeText(server.localPort.toString())
        Thread({
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use { it.soTimeout = 3000; it.getInputStream().readNBytes(MAX_INPUT) }
                        .decodeToString()
                        .lineSequence()
                        .filter { line -> line.isNotEmpty() && line.length < 64 * 1024 }
                        .take(8)
                        .forEach(onMessage)
                }
            }
        }, "single-instance").apply { isDaemon = true }.start()
        return true
    }

    const val SHOW = "zefir:show"
    private const val MAX_INPUT = 128 * 1024
}

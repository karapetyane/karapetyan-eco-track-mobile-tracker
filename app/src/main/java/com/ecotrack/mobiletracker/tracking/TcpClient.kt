package com.ecotrack.mobiletracker.tracking

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

class TcpClient(
    private val host: String,
    private val port: Int,
) {
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

    fun connect(timeoutMs: Int = 8000) {
        close()
        val s = Socket()
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(host, port), timeoutMs)
        socket = s
        writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII))
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false

    fun sendLine(line: String) {
        val w = writer ?: throw IllegalStateException("Not connected")
        w.write(line)
        w.write("\r\n")
        w.flush()
    }

    fun close() {
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        socket = null
    }
}


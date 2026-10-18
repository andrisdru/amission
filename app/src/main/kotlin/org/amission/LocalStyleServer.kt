package org.amission

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Serves one map style document on the phone's loopback address for the length of an offline download.
 *
 * The map engine downloads an offline area's style through its HTTP client, which cannot read file:// addresses
 * (it logs "Unable to parse resourceUrl" and the download hangs at 0%). The satellite style is built in the app
 * from the user's own key, so it has no web address of its own; this gives it a temporary one.
 *
 * The style contains the imagery key, and any app on the phone can reach 127.0.0.1, so the address carries a random
 * token, only that exact path is answered, and the server is closed as soon as the download ends.
 */
class LocalStyleServer(private val json: String) : AutoCloseable {
    private val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))

    /** Address to hand to the map engine. */
    val url: String = "http://127.0.0.1:${server.localPort}/$token/style.json"

    private val thread = Thread({ serve() }, "style-server").apply { isDaemon = true; start() }

    private fun serve() {
        while (!server.isClosed) {
            try {
                server.accept().use { handle(it) }
            } catch (e: IOException) {
                // closed while waiting, or a client went away mid-request
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 3_000
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        // Drain the headers; nothing in them matters.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val parts = requestLine.split(' ')
        val ok = parts.size >= 2 && parts[0] == "GET" && parts[1].substringBefore('?') == "/$token/style.json"
        val out = socket.getOutputStream()
        if (ok) {
            val body = json.toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
            out.write(body)
        } else {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        }
        out.flush()
    }

    override fun close() {
        try { server.close() } catch (e: IOException) { }
    }
}

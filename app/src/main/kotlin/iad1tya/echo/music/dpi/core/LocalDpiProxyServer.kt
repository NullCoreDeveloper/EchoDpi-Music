package iad1tya.echo.music.dpi.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

object LocalDpiProxyServer {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    var port: Int = 0
        private set

    fun start() {
        if (serverSocket != null && !serverSocket!!.isClosed) return
        try {
            serverSocket = ServerSocket(0, 50, Inet4Address.getByName("127.0.0.1"))
            port = serverSocket!!.localPort
            Timber.d("LocalDpiProxyServer started on port $port")

            scope.launch {
                while (isActive) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        Timber.e(e, "Proxy accept error")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start LocalDpiProxyServer")
        }
    }

    private suspend fun handleClient(clientSocket: Socket) = withContext(Dispatchers.IO) {
        var targetSocket: Socket? = null
        try {
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            val buffer = ByteArray(8192)
            var totalRead = 0
            var headersEndIndex = -1

            while (true) {
                val bytesRead = clientIn.read(buffer, totalRead, buffer.size - totalRead)
                if (bytesRead == -1) return@withContext
                totalRead += bytesRead
                
                // look for \r\n\r\n
                for (i in 0 until totalRead - 3) {
                    if (buffer[i].toInt() == 13 && buffer[i+1].toInt() == 10 &&
                        buffer[i+2].toInt() == 13 && buffer[i+3].toInt() == 10) {
                        headersEndIndex = i
                        break
                    }
                }
                
                if (headersEndIndex != -1) break
                
                if (totalRead >= buffer.size) {
                    return@withContext
                }
            }

            val requestHeader = String(buffer, 0, headersEndIndex, Charsets.US_ASCII)
            val lines = requestHeader.split("\r\n")
            if (lines.isEmpty()) return@withContext

            val requestLine = lines[0]
            if (!requestLine.startsWith("CONNECT", ignoreCase = true)) {
                clientOut.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray())
                return@withContext
            }

            val parts = requestLine.split(" ")
            if (parts.size < 2) return@withContext
            val hostPort = parts[1]
            val hpParts = hostPort.split(":")
            val host = hpParts[0]
            val targetPort = if (hpParts.size > 1) hpParts[1].toIntOrNull() ?: 443 else 443

            val addresses = DpiDns().lookup(host)
            var connected = false
            for (addr in addresses) {
                try {
                    targetSocket = Socket()
                    targetSocket.connect(InetSocketAddress(addr, targetPort), 10000)
                    connected = true
                    break
                } catch (e: Exception) {
                    Timber.d("Failed to connect to $addr: ${e.message}")
                }
            }

            if (!connected || targetSocket == null) {
                clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                return@withContext
            }

            clientOut.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
            clientOut.flush()

            val targetOutRaw = targetSocket.getOutputStream()
            val targetOut = DpiOutputStream(
                socket = targetSocket,
                delegate = targetOutRaw,
                strategy = DpiConfig.currentStrategy,
                params = DpiConfig.customParams
            )
            val targetIn = targetSocket.getInputStream()

            val initialRemaining = totalRead - headersEndIndex - 4
            if (initialRemaining > 0) {
                targetOut.write(buffer, headersEndIndex + 4, initialRemaining)
                targetOut.flush()
            }

            val job1 = launch { relay(clientIn, targetOut) }
            val job2 = launch { relay(targetIn, clientOut) }

            job1.join()
            job2.join()

        } catch (e: Exception) {
            // connection dropped, ignore
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { targetSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun relay(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        port = 0
    }
}

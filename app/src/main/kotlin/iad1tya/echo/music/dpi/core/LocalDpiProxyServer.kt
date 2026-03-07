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
            serverSocket = ServerSocket(39321, 50, Inet4Address.getByName("127.0.0.1"))
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

            val firstByte = clientIn.read()
            if (firstByte == -1) return@withContext

            var host: String = ""
            var targetPort: Int = 443
            var initialPayload: ByteArray? = null

            if (firstByte == 0x05) {
                // SOCKS5 Handshake
                val nMethods = clientIn.read()
                if (nMethods == -1) return@withContext
                val methods = ByteArray(nMethods)
                clientIn.read(methods)
                
                // No authentication requested
                clientOut.write(byteArrayOf(0x05, 0x00))
                clientOut.flush()

                // Request
                val ver = clientIn.read()
                val cmd = clientIn.read()
                val rsv = clientIn.read()
                val atyp = clientIn.read()

                if (ver != 0x05 || cmd != 0x01) { // Only CONNECT supported
                    clientOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    return@withContext
                }

                when (atyp) {
                    0x01 -> { // IPv4
                        val addr = ByteArray(4)
                        clientIn.read(addr)
                        host = java.net.InetAddress.getByAddress(addr).hostAddress
                    }
                    0x03 -> { // Domain name
                        val len = clientIn.read()
                        val addr = ByteArray(len)
                        clientIn.read(addr)
                        host = String(addr)
                    }
                    0x04 -> { // IPv6
                        val addr = ByteArray(16)
                        clientIn.read(addr)
                        host = java.net.InetAddress.getByAddress(addr).hostAddress
                    }
                    else -> return@withContext
                }

                targetPort = ((clientIn.read() and 0xFF) shl 8) or (clientIn.read() and 0xFF)
                
                // Success response (dummy BND.ADDR/PORT)
                clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientOut.flush()
            } else if (firstByte.toChar() == 'C') {
                // Potential HTTP CONNECT (starts with 'C' for CONNECT)
                val buffer = ByteArray(8192)
                buffer[0] = firstByte.toByte()
                var totalRead = 1
                var headersEndIndex = -1

                while (true) {
                    val bytesRead = clientIn.read(buffer, totalRead, buffer.size - totalRead)
                    if (bytesRead == -1) return@withContext
                    totalRead += bytesRead
                    
                    for (i in 0 until totalRead - 3) {
                        if (buffer[i].toInt() == 13 && buffer[i+1].toInt() == 10 &&
                            buffer[i+2].toInt() == 13 && buffer[i+3].toInt() == 10) {
                            headersEndIndex = i
                            break
                        }
                    }
                    if (headersEndIndex != -1) break
                    if (totalRead >= buffer.size) return@withContext
                }

                val requestHeader = String(buffer, 0, headersEndIndex, Charsets.US_ASCII)
                val lines = requestHeader.split("\r\n")
                if (lines.isEmpty() || !lines[0].startsWith("CONNECT", ignoreCase = true)) {
                    clientOut.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".toByteArray())
                    return@withContext
                }

                val parts = lines[0].split(" ")
                if (parts.size < 2) return@withContext
                val hostPort = parts[1]
                val hpParts = hostPort.split(":")
                host = hpParts[0]
                targetPort = if (hpParts.size > 1) hpParts[1].toIntOrNull() ?: 443 else 443
                
                clientOut.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                clientOut.flush()
                
                val remaining = totalRead - headersEndIndex - 4
                if (remaining > 0) {
                    initialPayload = buffer.copyOfRange(headersEndIndex + 4, totalRead)
                }
            } else {
                return@withContext
            }

            // Connection logic
            val addresses = DpiDns().lookup(host)
            var connected = false
            for (addr in addresses) {
                try {
                    targetSocket = Socket()
                    targetSocket.tcpNoDelay = true
                    targetSocket.connect(InetSocketAddress(addr, targetPort), 5000) // Reduced timeout for speed
                    connected = true
                    break
                } catch (e: Exception) {
                    Timber.d("Failed to connect to $addr: ${e.message}")
                }
            }

            if (!connected || targetSocket == null) {
                if (firstByte == 0x05) {
                    // SOCKS5 Failure could be sent here, but usually it's better to just close
                } else {
                    clientOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                }
                return@withContext
            }

            val targetOutRaw = targetSocket.getOutputStream()
            val targetOut = DpiOutputStream(
                socket = targetSocket,
                delegate = targetOutRaw,
                strategy = DpiConfig.currentStrategy,
                params = DpiConfig.customParams
            )
            val targetIn = targetSocket.getInputStream()

            if (initialPayload != null) {
                targetOut.write(initialPayload)
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

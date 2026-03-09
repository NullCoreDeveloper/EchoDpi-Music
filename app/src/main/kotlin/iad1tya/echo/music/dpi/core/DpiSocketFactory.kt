package iad1tya.echo.music.dpi.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

class DpiSocketFactory(
    private val delegate: SocketFactory,
    private val getStrategy: () -> DpiStrategy,
    private val getCustomParams: () -> String,
    private val getIsEnabled: () -> Boolean
) : SocketFactory() {

    override fun createSocket(): Socket {
        return wrapSocket(delegate.createSocket())
    }

    override fun createSocket(host: String?, port: Int): Socket {
        return wrapSocket(delegate.createSocket(host, port))
    }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        return wrapSocket(delegate.createSocket(host, port, localHost, localPort))
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket {
        return wrapSocket(delegate.createSocket(host, port))
    }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
        return wrapSocket(delegate.createSocket(address, port, localAddress, localPort))
    }

    private fun wrapSocket(socket: Socket): Socket {
        val strat = getStrategy()
        val param = getCustomParams()
        val enabled = getIsEnabled()

        if (!enabled || (strat == DpiStrategy.DEFAULT && param.isBlank())) {
            return socket
        }
        
        socket.tcpNoDelay = true
        return DpiSocketWrapper(socket, strat, param)
    }
}

class DpiSocketWrapper(
    private val delegate: Socket,
    private val strategy: DpiStrategy,
    private val params: String
) : Socket() {
    
    override fun connect(endpoint: java.net.SocketAddress?) = delegate.connect(endpoint)
    override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) = delegate.connect(endpoint, timeout)
    override fun bind(bindpoint: java.net.SocketAddress?) = delegate.bind(bindpoint)
    override fun getInetAddress(): InetAddress = delegate.inetAddress
    override fun getLocalAddress(): InetAddress = delegate.localAddress
    override fun getPort(): Int = delegate.port
    override fun getLocalPort(): Int = delegate.localPort
    override fun getRemoteSocketAddress() = delegate.remoteSocketAddress
    override fun getLocalSocketAddress() = delegate.localSocketAddress
    override fun getChannel() = delegate.channel
    override fun getInputStream(): InputStream = delegate.inputStream
    override fun getOutputStream(): OutputStream = DpiOutputStream(delegate, delegate.outputStream, strategy, params)
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) { delegate.setSoLinger(on, linger) }
    override fun getSoLinger(): Int = delegate.soLinger
    override fun sendUrgentData(data: Int) = delegate.sendUrgentData(data)
    override fun setOOBInline(on: Boolean) { delegate.oobInline = on }
    override fun getOOBInline(): Boolean = delegate.oobInline
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
    override fun getSendBufferSize(): Int = delegate.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun setTrafficClass(tc: Int) { delegate.trafficClass = tc }
    override fun getTrafficClass(): Int = delegate.trafficClass
    override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
    override fun getReuseAddress(): Boolean = delegate.reuseAddress
    override fun close() = delegate.close()
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isBound(): Boolean = delegate.isBound
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
}

class DpiOutputStream(
    private val socket: Socket,
    private val delegate: OutputStream,
    private val strategy: DpiStrategy,
    private val params: String
) : OutputStream() {
    private var isFirstPacket = true

    override fun write(b: Int) {
        delegate.write(b)
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (!isFirstPacket) {
            delegate.write(b, off, len)
            return
        }
        
        // Detect TLS Client Hello (Handshake [0x16] + Legacy Version [0x03 0x01/0x03/0x02] + length + Type [0x01])
        if (len > 5 && b[off] == 0x16.toByte() && b[off + 1] == 0x03.toByte() && b[off + 5] == 0x01.toByte()) {
            isFirstPacket = false
            try {
                val fullParams = if (params.isNotBlank()) params else strategy.params
                
                // Advanced Parser for sequential or single flags
                val splitSize = Regex("-s(\\d+)").find(fullParams)?.groupValues?.get(1)?.toIntOrNull() ?: 64
                val splitAtHost = fullParams.contains("+h") || fullParams.contains("+s")
                val useDisorder = fullParams.contains("-d1")
                val oobMode = when {
                    fullParams.contains("-o1") -> 1
                    fullParams.contains("-o2") -> 2
                    else -> 0
                }
                val oobChar = Regex("-e(\\d+)").find(fullParams)?.groupValues?.get(1)?.toIntOrNull()?.toByte() ?: 0xFF.toByte()
                val useFake = fullParams.contains("-f1")
                val delayMs = Regex("-d(\\d+)").find(fullParams)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

                var bytesWritten = 0
                
                // 1. Fake Packet Strategy
                if (useFake) {
                    val fakeSni = "google.com" // Default fake SNI if not specified
                    val fakeHello = createFakeClientHello(fakeSni)
                    delegate.write(fakeHello)
                    delegate.flush()
                }

                // 2. Disorder Strategy (send 1 byte first)
                if (useDisorder) {
                    delegate.write(b, off, 1)
                    delegate.flush()
                    bytesWritten = 1
                }

                // 3. Find SNI for split position if requested
                var currentSplitPos = splitSize
                if (splitAtHost) {
                    val sniPos = findSniPosition(b, off, len)
                    if (sniPos != -1) {
                        currentSplitPos = sniPos - off
                    }
                }

                // Process splitting (mostly for the first 1024 bytes of handshake)
                val splitLimit = if (len > 1024) 1024 else len

                while (bytesWritten < splitLimit) {
                    val remaining = splitLimit - bytesWritten
                    val size = if (remaining > currentSplitPos) currentSplitPos else remaining
                    
                    if (oobMode > 0 && bytesWritten == 0) {
                        delegate.write(b, off + bytesWritten, 1)
                        delegate.flush()
                        try { socket.sendUrgentData(oobChar.toInt()) } catch (e: Exception) {}
                        if (size > 1) {
                            delegate.write(b, off + bytesWritten + 1, size - 1)
                        }
                    } else {
                        delegate.write(b, off + bytesWritten, size)
                    }
                    
                    bytesWritten += size
                    
                    if (bytesWritten < splitLimit) {
                        if (delayMs > 0) Thread.sleep(delayMs)
                        delegate.flush()
                    }
                }
                
                // 4. Send remaining payload
                if (bytesWritten < len) {
                    delegate.write(b, off + bytesWritten, len - bytesWritten)
                }
                delegate.flush()
            } catch (e: Exception) {
                delegate.write(b, off, len)
            }
        } else {
            delegate.write(b, off, len)
        }
    }

    private fun findSniPosition(b: ByteArray, off: Int, len: Int): Int {
        // Search for SNI extension tag (0x00 0x00)
        // Skip Record Header (5), Handshake Header (4), Version (2), Random (32), Session ID (1+)
        if (len < 60) return -1
        for (i in off + 43 until off + len - 4) {
            if (b[i] == 0x00.toByte() && b[i+1] == 0x00.toByte() && b[i+2].toInt() > 0 && b[i+3] == 0x00.toByte()) {
                return i
            }
        }
        return -1
    }

    private fun createFakeClientHello(sni: String): ByteArray {
        // Minimal valid-ish TLS Client Hello with fake SNI
        val sniBytes = sni.toByteArray()
        val totalLen = 42 + sniBytes.size
        val out = ByteArray(totalLen + 5)
        out[0] = 0x16.toByte() // Handshake
        out[1] = 0x03.toByte()
        out[2] = 0x01.toByte()
        out[3] = (totalLen shr 8).toByte()
        out[4] = (totalLen and 0xFF).toByte()
        out[5] = 0x01.toByte() // Client Hello
        // ... rest is simplified for a "fake" packet that should just confuse DPI
        return out
    }
    
    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}

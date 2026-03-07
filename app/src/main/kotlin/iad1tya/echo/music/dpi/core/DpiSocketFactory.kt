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
        
        // Перехватываем только первые данные в сокете. Если это начало пакета TLS (0x16 0x03):
        if (len > 5 && b[off] == 0x16.toByte() && b[off + 1] == 0x03.toByte()) {
            isFirstPacket = false
            // ... (rest of the logic stays the same but isFirstPacket is now false at the end) ...
            try {
                when (strategy) {
                    DpiStrategy.SPLIT_1 -> {
                        delegate.write(b, off, 1)
                        delegate.flush()
                        Thread.sleep(2) // Reduced sleep
                        delegate.write(b, off + 1, len - 1)
                        delegate.flush()
                    }
                    DpiStrategy.SPLIT_2 -> {
                        delegate.write(b, off, 1)
                        delegate.flush()
                        Thread.sleep(2)
                        if (len > 4) {
                            delegate.write(b, off + 1, 3)
                            delegate.flush()
                            Thread.sleep(2)
                            delegate.write(b, off + 4, len - 4)
                            delegate.flush()
                        } else {
                            delegate.write(b, off + 1, len - 1)
                            delegate.flush()
                        }
                    }
                    DpiStrategy.OOB_INJECT -> {
                        delegate.write(b, off, 1)
                        delegate.flush()
                        try {
                            socket.sendUrgentData(0x00)
                        } catch (ignore: Exception) {} 
                        Thread.sleep(2)
                        delegate.write(b, off + 1, len - 1)
                        delegate.flush()
                    }
                    DpiStrategy.SNI_FRAG -> {
                        var offset = off
                        val end = off + len
                        // First small chunk
                        delegate.write(b, offset, 1)
                        delegate.flush()
                        Thread.sleep(2)
                        offset += 1
                        
                        // Remaining data in bigger chunks to avoid excessive overhead
                        while (offset < end) {
                            val chunkLen = if (end - offset > 100) 100 else end - offset
                            delegate.write(b, offset, chunkLen)
                            delegate.flush()
                            offset += chunkLen
                        }
                    }
                    DpiStrategy.PRO_MIX_1 -> {
                        // Mix of Split and OOB for stability
                        delegate.write(b, off, 1)
                        delegate.flush()
                        try { socket.sendUrgentData(0x00) } catch (e: Exception) {}
                        Thread.sleep(2)
                        delegate.write(b, off + 1, 3)
                        delegate.flush()
                        Thread.sleep(2)
                        delegate.write(b, off + 4, len - 4)
                        delegate.flush()
                    }
                    DpiStrategy.PRO_MIX_2 -> {
                        // Aggressive fragmentation
                        var offset = off
                        val end = off + len
                        val smallChunks = 4
                        for (i in 0 until smallChunks) {
                            if (offset < end) {
                                delegate.write(b, offset, 1)
                                delegate.flush()
                                Thread.sleep(1)
                                offset++
                            }
                        }
                        if (offset < end) {
                            delegate.write(b, offset, end - offset)
                            delegate.flush()
                        }
                    }
                    else -> {
                        delegate.write(b, off, len)
                    }
                }
            } catch (e: Exception) {
                delegate.write(b, off, len)
            }
        } else {
            // Если это не TLS Handshake или мы его уже прошли (что проверяется в начале функции)
            delegate.write(b, off, len)
        }
    }
    
    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}

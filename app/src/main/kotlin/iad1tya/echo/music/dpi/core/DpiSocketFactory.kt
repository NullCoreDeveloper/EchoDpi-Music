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
                // Проверяем все стратегии PRO как единую логику агрессивной фрагментации
                // так как они отличаются параметрами для CLI, но здесь мы делаем универсальный обход.
                
                // Агрессивная нарезка начала пакета (напр. первые 5 байт по 1 байту)
                val initialBytesToFragment = 5
                var bytesFragmented = 0
                
                while (bytesFragmented < initialBytesToFragment && bytesFragmented < len) {
                    delegate.write(b, off + bytesFragmented, 1)
                    delegate.flush()
                    // Очень маленькая задержка для ТСПУ
                    Thread.sleep(1)
                    bytesFragmented++
                }
                
                // Оставшаяся часть пакета
                if (bytesFragmented < len) {
                    delegate.write(b, off + bytesFragmented, len - bytesFragmented)
                    delegate.flush()
                }
                
                // КРИТИЧНО: Сразу сбрасываем флаг, чтобы следующие пакеты шли без задержек вообще
                isHandshakeDone = true
            } catch (e: Exception) {
                // Если что-то пошло не так, пишем как есть
                delegate.write(b, off, len)
                isHandshakeDone = true
            }
        } else {
            // Весь последующий трафик (после Handshake) идет напрямую на максимальной скорости
            delegate.write(b, off, len)
        }
    }
    
    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}

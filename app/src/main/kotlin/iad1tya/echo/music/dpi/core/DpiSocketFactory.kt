package iad1tya.echo.music.dpi.core

import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class DpiSocketFactory(
    private val delegate: SSLSocketFactory,
    private val getStrategy: () -> DpiStrategy,
    private val getCustomParams: () -> String,
    private val getIsEnabled: () -> Boolean
) : SSLSocketFactory() {

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
        val socket = delegate.createSocket(s, host, port, autoClose) as SSLSocket
        return wrapSocket(socket)
    }

    override fun createSocket(host: String?, port: Int): Socket {
        return wrapSocket(delegate.createSocket(host, port) as SSLSocket)
    }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket {
        return wrapSocket(delegate.createSocket(host, port, localHost, localPort) as SSLSocket)
    }

    override fun createSocket(host: InetAddress?, port: Int): Socket {
        return wrapSocket(delegate.createSocket(host, port) as SSLSocket)
    }

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket {
        return wrapSocket(delegate.createSocket(address, port, localAddress, localPort) as SSLSocket)
    }

    private fun wrapSocket(socket: SSLSocket): Socket {
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
    private val delegate: SSLSocket,
    private val strategy: DpiStrategy,
    private val params: String
) : SSLSocket() {

    override fun getOutputStream(): OutputStream {
        return DpiOutputStream(delegate.outputStream, strategy, params)
    }

    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun getEnabledCipherSuites(): Array<String> = delegate.enabledCipherSuites
    override fun setEnabledCipherSuites(p0: Array<String>?) { delegate.enabledCipherSuites = p0 }
    override fun getSupportedProtocols(): Array<String> = delegate.supportedProtocols
    override fun getEnabledProtocols(): Array<String> = delegate.enabledProtocols
    override fun setEnabledProtocols(p0: Array<String>?) { delegate.enabledProtocols = p0 }
    override fun getSession(): SSLSession = delegate.session
    override fun addHandshakeCompletedListener(p0: HandshakeCompletedListener?) = delegate.addHandshakeCompletedListener(p0)
    override fun removeHandshakeCompletedListener(p0: HandshakeCompletedListener?) = delegate.removeHandshakeCompletedListener(p0)
    override fun startHandshake() = delegate.startHandshake()
    override fun setUseClientMode(p0: Boolean) { delegate.useClientMode = p0 }
    override fun getUseClientMode(): Boolean = delegate.useClientMode
    override fun setNeedClientAuth(p0: Boolean) { delegate.needClientAuth = p0 }
    override fun getNeedClientAuth(): Boolean = delegate.needClientAuth
    override fun setWantClientAuth(p0: Boolean) { delegate.wantClientAuth = p0 }
    override fun getWantClientAuth(): Boolean = delegate.wantClientAuth
    override fun setEnableSessionCreation(p0: Boolean) { delegate.enableSessionCreation = p0 }
    override fun getEnableSessionCreation(): Boolean = delegate.enableSessionCreation
    override fun getSSLParameters(): SSLParameters = delegate.sslParameters
    override fun setSSLParameters(params: SSLParameters?) { delegate.sslParameters = params }

    // Унаследованные методы от Socket
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
    out: OutputStream,
    private val strategy: DpiStrategy,
    private val params: String
) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) {
        val useSplit = strategy == DpiStrategy.SPLIT_1 || strategy == DpiStrategy.SPLIT_2 || params.contains("-s")
        val useFake = strategy == DpiStrategy.FAKE_SPLIT || params.contains("-f")
        val useDisorder = strategy == DpiStrategy.DISORDER_1 || params.contains("-d")

        try {
            if (useFake) {
                // Пример инъекции Fake пакета - в реальности требует Raw Sockets
            }

            if (useSplit && len > 10) {
                val splitPos = if (strategy == DpiStrategy.SPLIT_1) len / 2 else 5
                
                out.write(b, off, splitPos)
                out.flush()
                
                Thread.sleep(10) // Форсируем фрагментацию
                
                out.write(b, off + splitPos, len - splitPos)
                out.flush()
            } else if (useDisorder) {
                // Disorder абстракция
                out.write(b, off, len)
            } else {
                out.write(b, off, len)
            }
        } catch (e: Exception) {
            out.write(b, off, len)
        }
    }
}

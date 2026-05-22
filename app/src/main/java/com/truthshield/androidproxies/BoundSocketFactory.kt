package com.truthshield.androidproxies

import android.net.Network
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

class BoundSocketFactory(private val network: Network) : SocketFactory() {

    override fun createSocket(): Socket {
        val s = Socket()
        network.bindSocket(s)
        return s
    }

    override fun createSocket(host: String, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createSocket().apply { connect(InetSocketAddress(host, port)) }

    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket {
        val s = createSocket()
        s.bind(InetSocketAddress(local, localPort))
        s.connect(InetSocketAddress(host, port))
        return s
    }

    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket {
        val s = createSocket()
        s.bind(InetSocketAddress(local, localPort))
        s.connect(InetSocketAddress(host, port))
        return s
    }
}

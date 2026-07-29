package com.youtv.app.requests

import okhttp3.Dns
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


class DnsCache(
    private val ttlMillis: Long = TimeUnit.MINUTES.toMillis(5),
) : Dns {
    private data class Entry(val addresses: List<InetAddress>, val expiresAt: Long)

    private val dnsCache = ConcurrentHashMap<String, Entry>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.isEmpty()) {
            return Dns.SYSTEM.lookup(hostname);
        }

        val now = System.currentTimeMillis()
        dnsCache[hostname]?.takeIf { it.expiresAt > now }?.let {
            return it.addresses
        }

        val addressesNew = Dns.SYSTEM.lookup(hostname)

        if (addressesNew.isNotEmpty()) {
            dnsCache[hostname] = Entry(addressesNew, now + ttlMillis)
        }

        return addressesNew
    }
}

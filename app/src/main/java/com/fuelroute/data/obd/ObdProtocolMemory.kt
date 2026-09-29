package com.fuelroute.data.obd

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide memory of the OBD bus protocol (`ATDPN` number, 1..C) each dongle last locked, so
 * the next session or background probe can start with `ATSP<n>` instead of a 5-20 s automatic
 * search (see [com.fuelroute.domain.obd.ElmProtocol.setProtocolCommand]).
 *
 * The in-memory map is what the engine and the probe read (no suspend, no injection needed);
 * [com.fuelroute.data.settings.ObdLinkStore] hydrates it from DataStore at app start and
 * installs [persister] so every change is written back.
 */
object ObdProtocolMemory {

    private val protocols = ConcurrentHashMap<String, Int>()

    /** Writes a change through to durable storage (null protocol = forget). Set by the store. */
    @Volatile
    var persister: ((address: String, protocol: Int?) -> Unit)? = null

    private fun key(address: String): String = address.trim().uppercase()

    /** The remembered protocol for [address], or null (unknown / not a real dongle). */
    fun get(address: String?): Int? = address?.let { protocols[key(it)] }

    /** Replaces the whole map (hydration from storage); does not call [persister]. */
    fun load(saved: Map<String, Int>) {
        protocols.clear()
        saved.forEach { (address, protocol) -> protocols[key(address)] = protocol }
    }

    /** Remembers the protocol [address] just locked; a null [protocol] is ignored. */
    fun remember(address: String?, protocol: Int?) {
        if (address.isNullOrBlank() || protocol == null) return
        val k = key(address)
        if (protocols.put(k, protocol) != protocol) persister?.invoke(k, protocol)
    }

    /** Forgets [address]'s protocol (e.g. it no longer locks). */
    fun forget(address: String?) {
        if (address.isNullOrBlank()) return
        val k = key(address)
        if (protocols.remove(k) != null) persister?.invoke(k, null)
    }
}

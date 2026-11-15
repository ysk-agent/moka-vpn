package dev.moriya.vpn.service

import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide holder for the OS-level tunnel state: whether the
 * system's TUN is actually up right now.
 *
 * This is deliberately separate from the backend session state
 * ([dev.moriya.vpn.tunnel.TunnelStatus]): the two can diverge — the
 * backend session can sit in Running while the system has torn the
 * VpnService down (found on device 2026-11-15, the state-divergence
 * defect) — so the UI shows them as separate lines and neither is
 * derived from the other.
 *
 * Fed by MokaVpnService: the backend reports TUN establishment and
 * teardown through TunHost.onVpnStatusChanged, and the service
 * forces "down" when it closes or is destroyed.
 */
object VpnTunnelState {

    @Volatile
    var isUp: Boolean = false
        private set

    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()

    fun set(up: Boolean) {
        if (isUp == up) return
        isUp = up
        listeners.forEach { it(up) }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}

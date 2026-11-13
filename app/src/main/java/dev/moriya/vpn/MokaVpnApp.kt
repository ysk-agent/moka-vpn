package dev.moriya.vpn

import android.app.Application
import dev.moriya.vpn.backend.libtailscale.LibtailscaleAppServices
import dev.moriya.vpn.backend.libtailscale.LibtailscaleBackend
import dev.moriya.vpn.tunnel.TunnelBackend

/**
 * Composition root — the ONLY place that names a concrete backend.
 *
 * Layer 1 sees [backend] typed as [TunnelBackend] (Layer 2). Swapping
 * backends later is: new Layer 3 implementation + change the one
 * construction below. Referencing the adapter class here is not a
 * libtailscale dependency; no libtailscale type appears in this file.
 */
class MokaVpnApp : Application() {

    lateinit var backend: TunnelBackend
        private set

    override fun onCreate() {
        super.onCreate()
        val libtailscaleBackend = LibtailscaleBackend()
        // The adapter's Android bridge (AppContext data source) is also
        // constructed here, inside the adapter package's factory, so
        // Layer 1 never builds backend-specific objects.
        libtailscaleBackend.init(LibtailscaleAppServices.create(this))
        backend = libtailscaleBackend
    }
}

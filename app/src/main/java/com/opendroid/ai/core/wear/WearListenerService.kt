package com.opendroid.ai.core.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Manifest-declared Data Layer listener for the D7 watch link (feature D7).
 *
 * Google Play services starts this service when a paired watch sends a
 * message on one of the `/opendroid/` paths, including while the app is
 * backgrounded. The class is a thin shell — all handling lives in
 * [WearCommandHandler], which owns the lock check and the routing onto the
 * existing agent paths. DataChanged events are not received here: the phone
 * is the only DataClient writer in this design, the watch only reads.
 */
@AndroidEntryPoint
class WearListenerService : WearableListenerService() {

    @Inject
    lateinit var commandHandler: WearCommandHandler

    override fun onMessageReceived(event: MessageEvent) {
        commandHandler.onMessageReceived(event)
    }
}

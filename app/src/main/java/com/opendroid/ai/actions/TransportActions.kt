package com.opendroid.ai.actions

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import com.opendroid.ai.actions.base.Action
import com.opendroid.ai.actions.base.ActionResult
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransportActions @Inject constructor() {

    fun getActions(): List<Action> = listOf(
        BookUberAction(),
        BookOlaAction(),
        GetDirectionsAction(),
        CheckTrafficAction(),
        CheckFlightAction(),
        TrackDeliveryAction()
    )

    private class BookUberAction : Action {
        override val name: String = "BOOK_UBER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val pickup = params["pickup"]?.trim()?.takeIf { it.isNotEmpty() }
            val destination = params["destination"] ?: return ActionResult(false, null, "destination is missing")
            return try {
                // No pickup given → Uber's my_location token. Passing the
                // literal string "Current Location" as a formatted address
                // made Uber geocode a place of that name. The ride-type name
                // ("UberGo") is not a product UUID, so product_id is omitted
                // entirely instead of sending a value Uber ignores.
                val pickupParam = if (pickup != null) {
                    "pickup[formatted_address]=${URLEncoder.encode(pickup, "UTF-8")}"
                } else {
                    "pickup=my_location"
                }
                val encDest = URLEncoder.encode(destination, "UTF-8")
                val uri = "uber://?action=setPickup&$pickupParam&dropoff[formatted_address]=$encDest".toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    // Non-success hand-off: the ride is only pre-filled —
                    // nothing is booked until the user confirms in Uber.
                    // The old full Success let the agent report the ride
                    // as arranged when it was merely staged (the Loop-1
                    // UPI/smart-home standard applies here too).
                    ActionResult.UserActionRequired(
                        "I've opened Uber with your trip to $destination filled in — review it and confirm in the app to actually book. No ride is booked yet."
                    )
                } else {
                    // Fallback to web link
                    val webUri = "https://m.uber.com/ul/?action=setPickup&$pickupParam&dropoff[formatted_address]=$encDest".toUri()
                    val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webIntent)
                    ActionResult.UserActionRequired(
                        "Uber isn't installed, so I opened its website with your trip to $destination filled in — review it and confirm there to actually book. No ride is booked yet."
                    )
                }
            } catch (e: Exception) {
                Log.e("BookUber", "Uber failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't book the Uber. Want to try again?")
            }
        }
    }

    private class BookOlaAction : Action {
        override val name: String = "BOOK_OLA"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val destination = params["destination"] ?: return ActionResult(false, null, "destination is missing")
            return try {
                val encDest = URLEncoder.encode(destination, "UTF-8")
                val uri = "olacabs://app/launch?lat=&lng=&utm_source=opendroid&drop_desc=$encDest".toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    // Non-success hand-off, same as Uber: the trip is only
                    // pre-filled — no ride exists until the user confirms
                    // in Ola.
                    ActionResult.UserActionRequired(
                        "I've opened Ola with your trip to $destination filled in — review it and confirm in the app to actually book. No ride is booked yet."
                    )
                } else {
                    val storeIntent = Intent(Intent.ACTION_VIEW, "market://details?id=com.olacabs.customer".toUri()).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(storeIntent)
                    ActionResult.UserActionRequired(
                        "Ola isn't installed — I opened its Play Store page so you can install it. No ride is booked."
                    )
                }
            } catch (e: Exception) {
                Log.e("BookOla", "Ola failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't book the Ola. Try again?")
            }
        }
    }

    private class GetDirectionsAction : Action {
        override val name: String = "GET_DIRECTIONS"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val to = params["to"] ?: return ActionResult(false, null, "destination (to) parameter missing")
            val from = params["from"] ?: ""
            val modeStr = params["mode"] ?: "drive"
            
            // The Maps URLs API accepts only these full words — the old
            // single-letter values were ignored and Maps silently defaulted
            // to driving directions for every mode.
            val travelMode = when (modeStr.lowercase()) {
                "walk", "walking", "on foot" -> "walking"
                "bike", "bicycle", "bicycling", "cycle", "cycling" -> "bicycling"
                "transit", "public transit", "bus", "train", "metro" -> "transit"
                else -> "driving"
            }
            
            return try {
                val encTo = URLEncoder.encode(to, "UTF-8")
                val encFrom = URLEncoder.encode(from, "UTF-8")
                val uri = if (from.isNotEmpty()) {
                    "https://www.google.com/maps/dir/?api=1&origin=$encFrom&destination=$encTo&travelmode=$travelMode".toUri()
                } else {
                    "https://www.google.com/maps/dir/?api=1&destination=$encTo&travelmode=$travelMode".toUri()
                }
                
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    ActionResult(true, "Here are your directions to $to!", null)
                } else {
                    // Browser fallback
                    val webIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webIntent)
                    ActionResult(true, "Maps app isn't installed, but I opened it in your browser!", null, true)
                }
            } catch (e: Exception) {
                Log.e("GetDirections", "Directions failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't get directions right now. Try again?")
            }
        }
    }

    private class CheckTrafficAction : Action {
        override val name: String = "CHECK_TRAFFIC"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val route = params["route"] ?: ""
            return try {
                val encRoute = URLEncoder.encode(route, "UTF-8")
                val uri = if (route.isNotEmpty()) {
                    "geo:0,0?q=$encRoute&z=10".toUri()
                } else {
                    "geo:0,0?q=traffic".toUri()
                }
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Here's the traffic for ${route.ifEmpty { "your area" }}!", null)
            } catch (e: Exception) {
                Log.e("CheckTraffic", "Traffic check failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't check traffic right now.")
            }
        }
    }

    private class CheckFlightAction : Action {
        override val name: String = "CHECK_FLIGHT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val flightNumber = params["flightNumber"] ?: return ActionResult(false, null, "flightNumber parameter missing")
            return try {
                val query = URLEncoder.encode("flight status $flightNumber", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$query".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Looking up flight $flightNumber for you!", null)
            } catch (e: Exception) {
                Log.e("CheckFlight", "Flight check failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't look up that flight. Try again?")
            }
        }
    }

    private class TrackDeliveryAction : Action {
        override val name: String = "TRACK_DELIVERY"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val trackingNumber = params["trackingNumber"] ?: return ActionResult(false, null, "trackingNumber parameter missing")
            val courier = params["courier"] ?: "google"
            return try {
                val searchUrl = when (courier.lowercase()) {
                    "fedex" -> "https://www.fedex.com/apps/fedextrack/?tracknumbers=$trackingNumber"
                    "ups" -> "https://www.ups.com/track?tracknum=$trackingNumber"
                    "usps" -> "https://tools.usps.com/go/TrackConfirmAction?tLabels=$trackingNumber"
                    "dhl" -> "https://www.dhl.com/en/express/tracking.html?AWB=$trackingNumber"
                    else -> "https://www.google.com/search?q=${URLEncoder.encode("$courier track $trackingNumber", "UTF-8")}"
                }
                val intent = Intent(Intent.ACTION_VIEW, searchUrl.toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Tracking your $courier package now!", null)
            } catch (e: Exception) {
                Log.e("TrackDelivery", "Tracking failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't track that delivery right now.")
            }
        }
    }
}

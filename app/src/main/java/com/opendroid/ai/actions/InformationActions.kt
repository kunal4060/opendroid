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
class InformationActions @Inject constructor() {

    fun getActions(): List<Action> = listOf(
        WebSearchAction(),
        GetWeatherAction(),
        GetNewsAction(),
        CalculateAction(),
        TranslateAction(),
        DefineWordAction(),
        ConvertUnitsAction(),
        CurrencyConvertAction(),
        CheckStockAction(),
        SummarizeUrlAction(),
        FactCheckAction()
    )

    private class WebSearchAction : Action {
        override val name: String = "WEB_SEARCH"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val query = params["query"] ?: return ActionResult(false, null, "query parameter is missing")
            return try {
                val encQuery = URLEncoder.encode(query, "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$encQuery".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Here's what I found for '$query'!", null)
            } catch (e: Exception) {
                Log.e("WebSearch", "Search failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't search right now. Try again?")
            }
        }
    }

    private class GetWeatherAction : Action {
        override val name: String = "GET_WEATHER"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            var location = params["location"]

            // If no location provided or it's a generic placeholder, try to resolve from device
            if (location == null || location == "current location" || location.isBlank()) {
                try {
                    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                    val hasLocationPermission = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

                    if (hasLocationPermission && locationManager != null) {
                        val lastLocation = locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                            ?: locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)

                        if (lastLocation != null) {
                            // Reverse geocode to city name
                            try {
                                val geocoder = android.location.Geocoder(context, java.util.Locale.getDefault())
                                @Suppress("DEPRECATION")
                                val addresses = geocoder.getFromLocation(lastLocation.latitude, lastLocation.longitude, 1)
                                location = addresses?.firstOrNull()?.locality
                                    ?: addresses?.firstOrNull()?.subAdminArea
                                    ?: addresses?.firstOrNull()?.adminArea
                            } catch (e: Exception) {
                                // Geocoder failed, use coordinates
                                location = "${lastLocation.latitude},${lastLocation.longitude}"
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w("GetWeather", "Location permission denied: ${e.message}")
                } catch (e: Exception) {
                    Log.w("GetWeather", "Location resolution failed: ${e.message}")
                }
            }

            // Final fallback
            if (location == null || location.isBlank()) {
                location = "my location"
            }

            return try {
                val weatherCondition = try {
                    val url = java.net.URL("https://wttr.in/${URLEncoder.encode(location, "UTF-8")}?format=%C,+%t")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 3000
                    connection.readTimeout = 3000
                    connection.inputStream.bufferedReader().use { it.readText().trim() }
                } catch (e: Exception) {
                    Log.e("GetWeather", "Failed to fetch weather from wttr.in: ${e.message}")
                    "Unknown"
                }

                val query = URLEncoder.encode("weather in $location", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$query".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                val resultMsg = if (weatherCondition != "Unknown") {
                    "The current weather in $location is $weatherCondition. Opened search for details."
                } else {
                    "Here's the weather for $location! Opened search for details."
                }
                ActionResult(true, resultMsg, null)
            } catch (e: Exception) {
                Log.e("GetWeather", "Weather failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't check the weather right now. Please check your internet connection.")
            }
        }
    }

    private class GetNewsAction : Action {
        override val name: String = "GET_NEWS"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val topic = params["topic"] ?: "latest news"
            return try {
                val query = URLEncoder.encode("news $topic", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://news.google.com/search?q=$query".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Here's the latest on '$topic'!", null)
            } catch (e: Exception) {
                Log.e("GetNews", "News failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't fetch the news right now.")
            }
        }
    }

    private class CalculateAction : Action {
        override val name: String = "CALCULATE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val expression = params["expression"] ?: return ActionResult(false, null, "expression parameter is missing")
            return try {
                val sanitized = expression.replace(" ", "")
                val result = evaluateSimpleExpression(sanitized)
                ActionResult(true, "$expression = $result", null)
            } catch (e: Exception) {
                // Fallback: Web search calculation
                val encExpr = URLEncoder.encode(expression, "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$encExpr".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(false, "Hmm, I couldn't calculate that directly. Let me Google it for you.", e.localizedMessage, true)
            }
        }

        private fun evaluateSimpleExpression(expr: String): Double {
            // Real recursive-descent evaluation: chained operators keep
            // their operands ("10-5-2" = 3, "2*3*4" = 24) with normal
            // precedence, parentheses and unary signs. Non-finite results
            // (e.g. "5/0" → Infinity) are rejected, not reported.
            val parser = ExpressionParser(expr)
            val value = parser.parseExpression()
            parser.expectEnd()
            if (!value.isFinite()) {
                throw ArithmeticException("Calculation result is not a finite number")
            }
            return value
        }

        private class ExpressionParser(private val input: String) {
            private var pos = 0

            fun parseExpression(): Double {
                var value = parseTerm()
                while (pos < input.length && (input[pos] == '+' || input[pos] == '-')) {
                    val op = input[pos++]
                    val rhs = parseTerm()
                    value = if (op == '+') value + rhs else value - rhs
                }
                return value
            }

            private fun parseTerm(): Double {
                var value = parseFactor()
                while (pos < input.length && (input[pos] == '*' || input[pos] == '/')) {
                    val op = input[pos++]
                    val rhs = parseFactor()
                    value = if (op == '*') value * rhs else value / rhs
                }
                return value
            }

            private fun parseFactor(): Double {
                if (pos < input.length && (input[pos] == '-' || input[pos] == '+')) {
                    val sign = if (input[pos++] == '-') -1.0 else 1.0
                    return sign * parseFactor()
                }
                if (pos < input.length && input[pos] == '(') {
                    pos++
                    val value = parseExpression()
                    if (pos >= input.length || input[pos] != ')') {
                        throw IllegalArgumentException("Missing closing parenthesis")
                    }
                    pos++
                    return value
                }
                val start = pos
                while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
                if (start == pos) {
                    throw IllegalArgumentException("Expected a number at position $pos")
                }
                return input.substring(start, pos).toDouble()
            }

            fun expectEnd() {
                if (pos != input.length) {
                    throw IllegalArgumentException("Unexpected character at position $pos")
                }
            }
        }
    }

    private class TranslateAction : Action {
        override val name: String = "TRANSLATE"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val text = params["text"] ?: return ActionResult(false, null, "text is missing")
            val from = params["from"] ?: "auto"
            val to = params["to"] ?: "en"
            return try {
                val encText = URLEncoder.encode(text, "UTF-8")
                val uri = "https://translate.google.com/?sl=$from&tl=$to&text=$encText&op=translate".toUri()
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Opening Google Translate for you!", null)
            } catch (e: Exception) {
                Log.e("Translate", "Translation failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't open the translator right now.")
            }
        }
    }

    private class DefineWordAction : Action {
        override val name: String = "DEFINE_WORD"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val word = params["word"] ?: return ActionResult(false, null, "word parameter is missing")
            return try {
                val query = URLEncoder.encode("define $word", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$query".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Here's the definition of '$word'!", null)
            } catch (e: Exception) {
                Log.e("DefineWord", "Definition failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't look that up right now.")
            }
        }
    }

    private class ConvertUnitsAction : Action {
        override val name: String = "CONVERT_UNITS"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val value = params["value"] ?: return ActionResult(false, null, "value is missing")
            val from = params["from"] ?: ""
            val to = params["to"] ?: ""
            return try {
                val query = URLEncoder.encode("convert $value $from to $to", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$query".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Converting $value $from to $to for you!", null)
            } catch (e: Exception) {
                Log.e("ConvertUnits", "Conversion failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't convert those units right now.")
            }
        }
    }

    private class CurrencyConvertAction : Action {
        override val name: String = "CURRENCY_CONVERT"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val amount = params["amount"] ?: return ActionResult(false, null, "amount is missing")
            val from = params["from"] ?: ""
            val to = params["to"] ?: ""
            return try {
                val query = URLEncoder.encode("convert $amount $from to $to", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$query".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Converting $amount $from to $to for you!", null)
            } catch (e: Exception) {
                Log.e("CurrencyConvert", "Currency failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't convert the currency right now.")
            }
        }
    }

    private class CheckStockAction : Action {
        override val name: String = "CHECK_STOCK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val symbol = params["symbol"] ?: return ActionResult(false, null, "symbol is missing")
            return try {
                val query = URLEncoder.encode("stock $symbol", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$query".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Here's the stock info for $symbol!", null)
            } catch (e: Exception) {
                Log.e("CheckStock", "Stock check failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't look up that stock right now.")
            }
        }
    }

    private class SummarizeUrlAction : Action {
        override val name: String = "SUMMARIZE_URL"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val url = params["url"] ?: return ActionResult(false, null, "url is missing")
            // Actually fetch the page and return an extractive summary of
            // its text — a plan step asking for a summary must receive one,
            // not a browser window. Fetch/extraction failures fall back to
            // opening the page as a non-success result.
            val summary = try {
                fetchPageSummary(url)
            } catch (e: Exception) {
                Log.e("SummarizeUrl", "Fetch failed: ${e.localizedMessage}")
                null
            }
            if (!summary.isNullOrBlank()) {
                return ActionResult(true, "Summary of $url:\n\n$summary", null)
            }
            return try {
                val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(false, "I couldn't fetch that page to summarise it, but I opened it for you.", "Page could not be fetched for summarisation", true)
            } catch (e: Exception) {
                Log.e("SummarizeUrl", "URL failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't summarise or open that link right now.")
            }
        }

        private fun fetchPageSummary(url: String): String? {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) OpenDroid")
            val html = connection.inputStream.bufferedReader().use { it.readText() }
            val text = html
                .replace(Regex("(?is)<script.*?</script>"), " ")
                .replace(Regex("(?is)<style.*?</style>"), " ")
                .replace(Regex("(?is)<[^>]+>"), " ")
                .replace(Regex("&[a-zA-Z#0-9]+;"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.length < 80) return null
            // Extractive summary: the first few complete sentences.
            val sentences = text.split(Regex("(?<=[.!?])\\s+"))
                .map { it.trim() }
                .filter { it.length > 30 }
                .take(3)
            val summary = if (sentences.isNotEmpty()) sentences.joinToString(" ") else text.take(600)
            return summary.take(1200)
        }
    }

    private class FactCheckAction : Action {
        override val name: String = "FACT_CHECK"
        override suspend fun execute(params: Map<String, String>, context: Context): ActionResult {
            val claim = params["claim"] ?: return ActionResult(false, null, "claim is missing")
            return try {
                val query = URLEncoder.encode("fact check $claim", "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=$query".toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionResult(true, "Let me check that for you!", null)
            } catch (e: Exception) {
                Log.e("FactCheck", "Fact check failed: ${e.localizedMessage}")
                ActionResult(false, null, "Couldn't check that right now.")
            }
        }
    }
}

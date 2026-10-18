package com.deepak.clipboardlinkbucket

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.regex.Pattern

data class LinkItem(
    val url: String,
    val capturedAt: String,
    var sent: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().put("url", url).put("capturedAt", capturedAt).put("sent", sent)

    companion object {
        fun fromJson(o: JSONObject) = LinkItem(
            url = o.optString("url"),
            capturedAt = o.optString("capturedAt"),
            sent = o.optBoolean("sent", false)
        )
    }
}

class MainActivity : Activity() {
    private val prefsName = "link_bucket_prefs"
    private val links = mutableListOf<LinkItem>()
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var countView: TextView
    private lateinit var statusView: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var webhookField: EditText
    private lateinit var tokenField: EditText
    private lateinit var saveConfigButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadLinks()
        buildUi()
        handleIncomingIntent(intent)
        renderLinks()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
        renderLinks()
    }

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xFFF5F6F7.toInt())
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)

        val title = TextView(this).apply {
            text = "Clipboard Link Bucket"
            textSize = 24f
            setTextColor(0xFF17212B.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        content.addView(title)
        content.addView(label("X/Twitter post links collect karein, duplicates skip honge."))
        countView = label("0 links")
        countView.textSize = 16f
        countView.setTypeface(null, Typeface.BOLD)
        content.addView(countView)

        content.addView(sectionTitle("Google Sheets connection"))
        webhookField = EditText(this).apply {
            hint = "Apps Script Web App URL"
            setSingleLine(true)
            textSize = 14f
        }
        content.addView(webhookField, matchWrap())
        tokenField = EditText(this).apply {
            hint = "Secret token"
            setSingleLine(true)
            textSize = 14f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        content.addView(tokenField, matchWrap())

        val saved = getSharedPreferences(prefsName, MODE_PRIVATE)
        webhookField.setText(saved.getString("webhook", "") ?: "")
        tokenField.setText(saved.getString("secret", "") ?: "")

        saveConfigButton = button("Save Google Sheets Settings") {
            saveConfig()
            setStatus("Settings saved on this phone.")
        }
        content.addView(saveConfigButton, matchWrap())

        content.addView(sectionTitle("Collect links"))
        content.addView(button("Collect Current Clipboard") { collectClipboard() }, matchWrap())
        content.addView(button("Send Pending Links to Google Sheets") { sendPendingToSheets() }, matchWrap())
        content.addView(button("Share All Links to WhatsApp / Apps") { shareLinks() }, matchWrap())
        content.addView(button("Copy Pending Links") { copyPending() }, matchWrap())
        content.addView(button("Clear Bucket") { clearBucket() }, matchWrap())

        statusView = label("Ready. X mein Share → Link Bucket use karna sabse reliable hai.")
        statusView.setTextColor(0xFF526170.toInt())
        content.addView(statusView)

        content.addView(sectionTitle("Collected links"))
        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(listContainer)

        page.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(page)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val added = addLinksFromText(text)
        if (added > 0) {
            setStatus("Share menu se $added naya link collect hua.")
            Toast.makeText(this, "$added link(s) added", Toast.LENGTH_SHORT).show()
        } else {
            setStatus("Shared text mein naya X/Twitter post link nahi mila (ya duplicate tha).")
        }
    }

    private fun collectClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = if (clipboard.hasPrimaryClip()) {
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        } else null
        if (text.isNullOrBlank()) {
            setStatus("Clipboard empty hai. X par Share → Copy link karke try karein.")
            return
        }
        val added = addLinksFromText(text)
        setStatus(if (added > 0) "$added naya link collect hua." else "Koi naya X post link nahi mila; duplicates skip hote hain.")
        renderLinks()
    }

    private fun addLinksFromText(text: String): Int {
        val found = extractStatusLinks(text)
        var added = 0
        for (url in found) {
            if (links.none { it.url == url }) {
                links.add(LinkItem(url, timestamp()))
                added++
            }
        }
        if (added > 0) saveLinks()
        return added
    }

    private fun extractStatusLinks(text: String): List<String> {
        val pattern = Pattern.compile(
            "https?://(?:www\\.|mobile\\.)?(?:x\\.com|twitter\\.com)/[^\\s<>\\\"']+",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(text)
        val results = linkedSetOf<String>()
        while (matcher.find()) {
            var raw = matcher.group().trim().trim('<', '>')
            raw = raw.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')
            val uri = android.net.Uri.parse(raw)
            val host = (uri.host ?: "").lowercase(Locale.ROOT)
            if (host !in setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com", "mobile.x.com")) continue
            val path = uri.path ?: ""
            val isStatus = Regex("^/[^/]+/status/\\d+/?$", RegexOption.IGNORE_CASE).matches(path) ||
                Regex("^/i/web/status/\\d+/?$", RegexOption.IGNORE_CASE).matches(path)
            if (!isStatus) continue
            val cleanPath = path.trimEnd('/')
            results.add("https://x.com$cleanPath")
        }
        return results.toList()
    }

    private fun sendPendingToSheets() {
        saveConfig()
        val pending = links.filter { !it.sent }
        if (pending.isEmpty()) {
            setStatus("Koi pending links nahi hain.")
            return
        }
        val endpoint = webhookField.text.toString().trim()
        val secret = tokenField.text.toString().trim()
        if (endpoint.isBlank() || secret.isBlank()) {
            setStatus("Pehle Apps Script URL aur secret token set karein.")
            return
        }
        setStatus("${pending.size} links Google Sheets mein bheje ja rahe hain…")
        executor.execute {
            try {
                val payload = JSONObject()
                payload.put("token", secret)
                val array = JSONArray()
                pending.forEach { item ->
                    array.put(JSONObject()
                        .put("url", item.url)
                        .put("capturedAt", item.capturedAt))
                }
                payload.put("links", array)

                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 20000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val responseText = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                connection.disconnect()
                val response = JSONObject(responseText.ifBlank { "{}" })
                if (code !in 200..299 || !response.optBoolean("ok", false)) {
                    throw IllegalStateException(response.optString("error", "HTTP $code: $responseText"))
                }
                runOnUiThread {
                    pending.forEach { item -> links.find { it.url == item.url }?.sent = true }
                    saveLinks()
                    renderLinks()
                    setStatus("${pending.size} links Google Sheets mein add ho gaye.")
                    Toast.makeText(this, "Export successful: ${pending.size} links", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setStatus("Export fail hua: ${e.message ?: "network error"}. Links pending hi rahenge.")
                    Toast.makeText(this, "Export failed. Links safe hain.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareLinks() {
        if (links.isEmpty()) {
            setStatus("Share karne ke liye pehle links collect karein.")
            return
        }
        val body = buildString {
            append("Collected X/Twitter posts\n\n")
            links.forEachIndexed { index, item ->
                append("${index + 1}. ${item.url}\n")
            }
        }.trim()
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Collected X/Twitter links")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(sendIntent, "Share links"))
    }

    private fun copyPending() {
        val pending = links.filter { !it.sent }
        if (pending.isEmpty()) {
            setStatus("Koi pending links nahi hain.")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("X links", pending.joinToString("\n") { it.url }))
        setStatus("${pending.size} pending links clipboard mein copy ho gaye.")
    }

    private fun clearBucket() {
        links.clear()
        saveLinks()
        renderLinks()
        setStatus("Local bucket clear ho gaya. Google Sheet ki rows delete nahi hui.")
    }

    private fun renderLinks() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        val pending = links.count { !it.sent }
        countView.text = "${links.size} total  •  $pending pending  •  ${links.size - pending} sent"
        if (links.isEmpty()) {
            listContainer.addView(label("Abhi koi link nahi. X ke Share menu se Link Bucket choose karein."))
            return
        }
        links.asReversed().forEach { item ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(0xFFFFFFFF.toInt())
            }
            val linkText = TextView(this).apply {
                text = item.url
                textSize = 13f
                setTextColor(0xFF1558A6.toInt())
                setTextIsSelectable(true)
            }
            card.addView(linkText)
            val status = TextView(this).apply {
                text = "${item.capturedAt}  •  ${if (item.sent) "SENT" else "PENDING"}  •  tap to remove"
                textSize = 11f
                setTextColor(0xFF526170.toInt())
                setPadding(0, dp(4), 0, 0)
            }
            card.addView(status)
            card.setOnClickListener {
                links.remove(item)
                saveLinks()
                renderLinks()
                setStatus("Selected link local bucket se remove hua.")
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dp(7)
            listContainer.addView(card, params)
        }
    }

    private fun saveConfig() {
        getSharedPreferences(prefsName, MODE_PRIVATE).edit()
            .putString("webhook", webhookField.text.toString().trim())
            .putString("secret", tokenField.text.toString().trim())
            .apply()
    }

    private fun loadLinks() {
        val raw = getSharedPreferences(prefsName, MODE_PRIVATE).getString("links", "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) links.add(LinkItem.fromJson(array.getJSONObject(i)))
        } catch (_: Exception) {
            links.clear()
        }
    }

    private fun saveLinks() {
        val array = JSONArray()
        links.forEach { array.put(it.toJson()) }
        getSharedPreferences(prefsName, MODE_PRIVATE).edit().putString("links", array.toString()).apply()
    }

    private fun setStatus(text: String) {
        if (::statusView.isInitialized) statusView.text = text
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFF27313A.toInt())
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFF17212B.toInt())
        setPadding(0, dp(14), 0, dp(5))
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(6) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

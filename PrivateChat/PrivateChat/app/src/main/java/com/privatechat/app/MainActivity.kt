package com.privatechat.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.privatechat.app.crypto.SecureSession
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var peer: NetworkPeer
    private lateinit var statusText: TextView
    private lateinit var roomCodeText: TextView
    private lateinit var hostInfoText: TextView
    private lateinit var ipInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var chatLog: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var scroll: ScrollView
    private val ink = Color.rgb(27, 42, 56)
    private val muted = Color.rgb(99, 117, 132)
    private val canvas = Color.rgb(245, 248, 251)
    private val blue = Color.rgb(29, 108, 220)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = canvas
        window.navigationBarColor = canvas
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        peer = NetworkPeer(
            onState = { state -> mainHandler.post { statusText.text = state } },
            onMessage = { message -> mainHandler.post { addMessage("Peer", message, false) } }
        )
        buildScreen()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(canvas)
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        root.addView(text("PrivateChat", 28, ink, true))
        root.addView(text("Direct, encrypted text between two Android phones", 14, muted, false).withMargins(0, dp(3), 0, dp(14)))

        val statusCard = card()
        statusCard.addView(text("CONNECTION", 11, muted, true))
        statusText = text("Not connected", 14, ink, true)
        statusCard.addView(statusText.withMargins(0, dp(6), 0, 0))
        root.addView(statusCard.withMargins(0, 0, 0, dp(12)))

        val hostCard = card()
        hostCard.addView(text("Create a room", 18, ink, true))
        hostCard.addView(text("Start the listener on this phone, then share the code and Wi-Fi IP.", 13, muted, false).withMargins(0, dp(4), 0, dp(9)))
        roomCodeText = text("Room Code: -", 20, blue, true)
        hostCard.addView(roomCodeText)
        hostInfoText = text("Local IP: ${localIpv4() ?: "not available"}   |   Port: ${NetworkPeer.PORT}", 13, muted, false).withMargins(0, dp(4), 0, dp(7))
        hostCard.addView(hostInfoText)
        val hostButton = button("Start hosting")
        hostButton.setOnClickListener {
            val code = SecureSession.newRoomCode()
            roomCodeText.text = "Room Code: $code"
            hostInfoText.text = "Local IP: ${localIpv4() ?: "not available"}   |   Port: ${NetworkPeer.PORT}"
            peer.startHosting(code)
            addMessage("System", "Room opened. Share the code privately.", false)
        }
        hostCard.addView(hostButton)
        root.addView(hostCard.withMargins(0, 0, 0, dp(12)))

        val joinCard = card()
        joinCard.addView(text("Join a room", 18, ink, true))
        joinCard.addView(text("Enter the host phone's reachable IP address and Room Code.", 13, muted, false).withMargins(0, dp(4), 0, dp(8)))
        ipInput = edit("Host IP address", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        codeInput = edit("12-character Room Code", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        joinCard.addView(ipInput.withMargins(0, 0, 0, dp(7)))
        joinCard.addView(codeInput.withMargins(0, 0, 0, dp(7)))
        val joinButton = button("Connect directly")
        joinButton.setOnClickListener {
            val address = ipInput.text.toString().trim()
            val code = codeInput.text.toString()
            if (address.isBlank()) ipInput.error = "Enter the host IP address"
            else try {
                SecureSession.normalizeCode(code)
                peer.connect(address, code)
            } catch (error: IllegalArgumentException) {
                codeInput.error = error.message
            }
        }
        joinCard.addView(joinButton)
        root.addView(joinCard.withMargins(0, 0, 0, dp(12)))

        val chatCard = card()
        chatCard.addView(text("Messages", 18, ink, true))
        chatCard.addView(text("Messages stay in memory while this screen is open.", 12, muted, false).withMargins(0, dp(3), 0, dp(7)))
        scroll = ScrollView(this).apply { isFillViewport = true }
        chatLog = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(chatLog)
        chatCard.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        messageInput = edit("Write a message", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES).apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEND) { sendCurrentMessage(); true } else false
            }
        }
        row.addView(messageInput, LinearLayout.LayoutParams(0, dp(48), 1f).also { it.marginEnd = dp(8) })
        row.addView(button("Send").apply { setOnClickListener { sendCurrentMessage() } })
        chatCard.addView(row.withMargins(0, dp(8), 0, 0))
        root.addView(chatCard)
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun sendCurrentMessage() {
        val message = messageInput.text.toString().trim()
        if (message.isEmpty()) return
        if (peer.sendMessage(message)) {
            addMessage("You", message, true)
            messageInput.text.clear()
        } else statusText.text = "Not connected. Connect to a peer before sending."
    }

    private fun addMessage(sender: String, body: String, outgoing: Boolean) {
        if (!::chatLog.isInitialized) return
        val line = TextView(this).apply {
            text = "${timeNow()}  $sender: $body"
            textSize = 14f
            setTextColor(if (sender == "System") muted else ink)
            setPadding(dp(9), dp(7), dp(9), dp(7))
            setBackgroundColor(if (outgoing) Color.rgb(226, 239, 255) else Color.WHITE)
        }
        chatLog.addView(line.withMargins(0, 0, 0, dp(5)))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun timeNow() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun localIpv4(): String? = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val addresses = interfaces.nextElement().inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) return address.hostAddress
            }
        }
        null
    } catch (_: Exception) { null }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        setBackgroundColor(Color.WHITE)
        elevation = dp(2).toFloat()
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun edit(hintText: String, type: Int) = EditText(this).apply {
        hint = hintText
        inputType = type
        textSize = 15f
        setPadding(dp(10), 0, dp(10), 0)
        setBackgroundColor(canvas)
    }

    private fun button(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        setBackgroundColor(blue)
    }

    private fun View.withMargins(left: Int, top: Int, right: Int, bottom: Int): View {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(left, top, right, bottom)
        }
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        peer.stop()
        super.onDestroy()
    }
}
# PrivateChat Version 1

Minimal Android-to-Android private text chat. It has no account system, backend, relay, or message database. The host phone listens on TCP port 45678; one peer connects directly. Messages exist only in process memory while the app is open.

## File list

    PrivateChat/
    |-- settings.gradle.kts
    |-- build.gradle.kts
    |-- gradle.properties
    |-- .gitignore
    |-- README.md
    `-- app/
        |-- build.gradle.kts
        `-- src/main/
            |-- AndroidManifest.xml
            `-- java/com/privatechat/app/
                |-- MainActivity.kt
                |-- NetworkPeer.kt
                `-- crypto/SecureSession.kt

The UI is built in Kotlin in MainActivity.kt, so a separate XML layout is not needed.

## Network limits

- On the same Wi-Fi, use the host phone's local IPv4 address and the Room Code shown by the host.
- The app makes a direct TCP socket. It does not use a rendezvous, signaling, relay, or storage service.
- A Room Code does not discover or route to a phone. Over the public internet, the host needs a reachable public address and inbound TCP port 45678 forwarded to the phone. The router must allow phone port forwarding, and the ISP must not use carrier-grade NAT. Many mobile networks and public Wi-Fi networks block inbound connections. In these cases direct connection will fail; this app has no fallback.
- Keep the host app open in the foreground. Android may stop network work when the process is backgrounded or killed. A reconnect uses a fresh ephemeral key exchange; messages sent while disconnected are not queued.
- This version supports one peer and text messages up to 4096 UTF-8 bytes.

## Security design and limits

- The host generates a fresh 12-character Base32 Room Code using SecureRandom (60 bits of entropy). Share it through a private channel.
- Both phones authenticate role-tagged ephemeral X25519 public keys with HMAC-SHA-256 using a scrypt-derived Room Code key. HKDF-SHA-256 derives separate send and receive keys. Each message is protected with ChaCha20-Poly1305 and a monotonically increasing per-direction nonce.
- The code is not a standards-reviewed PAKE. Its handshake authenticator permits offline code guesses; scrypt makes guesses more expensive and the generated code has 60 bits of entropy. Do not replace generated codes with short human-chosen passwords. This implementation has not had an independent security audit.
- No long-term identity keys exist. A successful connection authenticates possession of the shared code, not a person's real-world identity.
- Messages are not written to app storage or a server. Screenshots, OS memory handling, rooted devices, or a compromised endpoint are outside this transport protection.

## Build and install

1. Install Android Studio with Android SDK Platform 35 and JDK 17.
2. In Android Studio choose Open, then select the PrivateChat folder.
3. Allow Gradle sync and dependency download. The project uses Android Gradle Plugin 8.7.3, Kotlin 2.0.21, and Bouncy Castle 1.86.
4. Choose Build > Build Bundle(s) / APK(s) > Build APK(s). Android Studio writes the debug APK under app/build/outputs/apk/debug/app-debug.apk.
5. Install the APK on both phones, allow installation from your chosen source, connect both phones to the same Wi-Fi, and keep PrivateChat open.

## Two-phone test

1. On phone A tap Start hosting. It shows the 12-character Room Code, the phone's local IP, and port 45678.
2. On phone B enter phone A's local IP and Room Code, then tap Connect directly.
3. Wait for Connected securely on both phones. Send messages in both directions and verify the text appears on the other phone.
4. Stop/reopen phone B's network or briefly disconnect Wi-Fi. Phone B retries every 2.5 seconds; phone A waits for a replacement connection. Reconnect Wi-Fi and confirm a fresh secure connection is established.
5. Repeat with one character changed in the Room Code. Authentication should fail and no chat session should be established.

## Troubleshooting

- Connection refused or timed out: confirm both devices are on the same non-isolated Wi-Fi, use the host's current local IP, and allow TCP 45678 through any firewall. Guest Wi-Fi often blocks phone-to-phone traffic.
- Peer disconnected or authentication failed: check the exact Room Code. Use the current code from the host; starting a new host room creates a new code.
- Host IP not shown: ensure Wi-Fi is connected, then re-open the screen. You can enter the phone's Wi-Fi IPv4 address manually.
- Gradle sync fails: confirm JDK 17 is selected in Android Studio, SDK Platform 35 is installed, and internet access is available for first-time dependency downloads.
- Internet test cannot connect: a public IP alone is insufficient. Configure router TCP port forwarding to the host phone and verify the ISP provides a public IPv4 address rather than CGNAT. Direct inbound IPv6 could work only if both networks, device firewall, and addressing permit it; this app does not discover IPv6 addresses.

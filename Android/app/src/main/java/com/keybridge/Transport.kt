package com.keybridge

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@SuppressLint("MissingPermission")
class Transport(private val context: Context, private val status: (String) -> Unit) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = context.getSharedPreferences("keybridge_trust", Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val uuid = UUID.fromString("e7d12867-9072-47f6-ae29-c12272ca5b37")
    private val keyAlias = "keybridge_trust_key_v1"
    @Volatile private var socket: BluetoothSocket? = null
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false
    private var desiredHost: BluetoothDevice? = null

    fun devices(): List<BluetoothDevice> = adapter?.bondedDevices?.sortedBy { it.name ?: it.address } ?: emptyList()
    fun savedDevice(): BluetoothDevice? = prefs.getString("device_address", null)?.let { address -> runCatching { adapter?.getRemoteDevice(address) }.getOrNull() }
    fun deviceName(d: BluetoothDevice) = d.name ?: d.address
    fun available() = adapter?.isEnabled == true
    fun isTrusted() = loadToken() != null && prefs.getString("device_address", null) != null
    fun connected(hidMode: Boolean): Boolean = if (hidMode) host != null else socket?.isConnected == true

    fun closeSocket() { try { socket?.close() } catch (_: Exception) {}; socket = null }

    suspend fun connect(d: BluetoothDevice, pin: String): String = withContext(Dispatchers.IO) {
        closeSocket()
        try {
            val s = d.createRfcommSocketToServiceRecord(uuid)
            socket = s
            val deadline = timer.schedule({ try { s.close() } catch (_: Exception) {} }, 20, TimeUnit.SECONDS)
            try {
                s.connect()
                val result = if (loadToken() == null) pair(s, pin) else authenticate(s)
                if (result.first) {
                    prefs.edit().putString("device_address", d.address).apply()
                    result.second
                } else { closeSocket(); result.second }
            } finally { deadline.cancel(false) }
        } catch (e: Exception) {
            closeSocket()
            "藍牙連線失敗：${e.message ?: "未知錯誤"}"
        }
    }

    suspend fun sendText(text: String): String = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext "尚未連線"
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (text.isEmpty()) return@withContext "沒有文字可傳送"
        if (!validUtf16(text)) return@withContext "文字包含無效字元，請編輯後再傳送"
        if (bytes.size > 1024 * 1024) return@withContext "文字超過 1 MiB 限制"
        val deadline = timer.schedule({ try { s.close() } catch (_: Exception) {} }, 30, TimeUnit.SECONDS)
        try {
            val payload = ByteBuffer.allocate(16 + bytes.size).put(randomId()).put(bytes).array()
            writeFrame(s.outputStream, 3, payload)
            val response = readFrame(s.inputStream)
            if (response == null || response.first != 0x83.toByte() || response.second.size != 1) {
                closeSocket(); "傳送結果無法確認；請先檢查電腦再重送"
            } else when (response.second[0].toInt()) {
                1 -> "電腦已接收並送出輸入事件；請檢查目標欄位"
                2 -> "部分輸入事件送出；請檢查電腦，勿直接重送"
                else -> "電腦拒絕或無法處理；請檢查目標欄位"
            }
        } catch (e: Exception) {
            closeSocket(); "傳送逾時或連線中斷，結果不明；請先檢查電腦再重送"
        } finally { deadline.cancel(false) }
    }

    fun forgetTrust() {
        closeSocket()
        prefs.edit().remove("device_address").remove("token").apply()
    }

    private fun pair(s: BluetoothSocket, pin: String): Pair<Boolean, String> {
        if (!pin.matches(Regex("[0-9]{6}"))) return false to "請輸入 Windows 顯示的 6 位數代碼"
        val deviceId = phoneId()
        val challenge = ByteArray(32).also(secureRandom::nextBytes)
        writeFrame(s.outputStream, 1, pin.toByteArray(Charsets.US_ASCII) + deviceId + challenge)
        val response = readFrame(s.inputStream) ?: return false to "首次配對沒有收到 Windows 回應"
        if (response.first != 0x81.toByte() || response.second.size != 33 || response.second[0].toInt() != 1) return false to "首次配對失敗；請確認代碼並重試"
        saveToken(response.second.copyOfRange(1, 33))
        return true to "首次配對成功；已記住這台電腦，下次會自動連線"
    }

    private fun authenticate(s: BluetoothSocket): Pair<Boolean, String> {
        val token = loadToken() ?: return false to "尚未完成首次配對"
        val challenge = ByteArray(32).also(secureRandom::nextBytes)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(token, "HmacSHA256")) }.doFinal(challenge)
        writeFrame(s.outputStream, 2, phoneId() + challenge + mac)
        val response = readFrame(s.inputStream)
        return if (response?.first == 0x82.toByte() && response.second.size == 1 && response.second[0].toInt() == 1) {
            true to "已自動連線：信任驗證完成"
        } else false to "Windows 未接受已儲存的信任；請在兩端解除信任後重新配對"
    }

    private fun writeFrame(out: java.io.OutputStream, type: Int, payload: ByteArray) {
        require(payload.size <= 1024 * 1024)
        DataOutputStream(out).apply {
            write("KB03".toByteArray(Charsets.US_ASCII)); writeByte(type); writeInt(payload.size); write(payload); flush()
        }
    }

    private fun readFrame(input: java.io.InputStream): Pair<Byte, ByteArray>? {
        val data = DataInputStream(input)
        val magic = ByteArray(4); data.readFully(magic)
        if (!magic.contentEquals("KB03".toByteArray(Charsets.US_ASCII))) return null
        val type = data.readByte(); val length = data.readInt()
        if (length < 0 || length > 1024 * 1024) return null
        val payload = ByteArray(length); data.readFully(payload)
        return type to payload
    }

    private fun phoneId(): ByteArray {
        val saved = prefs.getString("phone_id", null)
        if (saved != null) return Base64.decode(saved, Base64.NO_WRAP)
        return ByteArray(16).also {
            secureRandom.nextBytes(it)
            prefs.edit().putString("phone_id", Base64.encodeToString(it, Base64.NO_WRAP)).apply()
        }
    }

    private fun loadToken(): ByteArray? = runCatching {
        val encoded = prefs.getString("token", null) ?: return null
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = packed.copyOfRange(0, 12)
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }.doFinal(packed.copyOfRange(12, packed.size))
    }.getOrNull()

    private fun saveToken(token: ByteArray) {
        val iv = ByteArray(12).also(secureRandom::nextBytes)
        val encrypted = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv)) }.doFinal(token)
        prefs.edit().putString("token", Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)).apply()
    }

    private fun key(): java.security.Key {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keys.containsAlias(keyAlias)) return keys.getKey(keyAlias, null)
        val generator = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private fun randomId() = ByteArray(16).also(secureRandom::nextBytes)
    private fun validUtf16(text: String): Boolean {
        var i = 0
        while (i < text.length) { val c = text[i]; if (Character.isHighSurrogate(c)) { if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return false; i += 2 } else { if (Character.isLowSurrogate(c)) return false; i++ } }
        return true
    }

    fun startHid() {
        desiredHost = null
        registerHid()
    }

    fun startHid(d: BluetoothDevice) {
        desiredHost = d
        if (hid != null && registered) { hid?.connect(d); return }
        registerHid()
    }

    private fun registerHid() {
        val ok = adapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice; hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings("KEYBRIDGE", "Keyboard", "KEYBRIDGE", BluetoothHidDevice.SUBCLASS1_KEYBOARD, descriptor)
                if (!h.registerApp(sdp, null, null, context.mainExecutor, callback)) status("HID 註冊請求失敗；此手機可能不支援")
            }
            override fun onServiceDisconnected(profile: Int) { hid = null; registered = false; host = null; status("HID 服務已中斷") }
        }, BluetoothProfile.HID_DEVICE) ?: false
        if (!ok) status("無法取得 HID 服務；此手機可能不支援") else status("正在註冊 HID，等待系統回應")
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) { registered = isRegistered; if (isRegistered) { status(if (desiredHost == null) "手機鍵盤已啟用；請在 Windows 新增藍牙裝置並選擇 KEYBRIDGE" else "HID 已註冊，正在連線"); desiredHost?.let { if (hid?.connect(it) != true) status("HID 連線請求失敗") } } else { host = null; status("HID 已取消註冊或系統不支援") } }
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) { host = if (state == BluetoothProfile.STATE_CONNECTED) device else null; if (host != null) device?.let { prefs.edit().putString("hid_device_address", it.address).apply() }; status(if (host != null) "HID 鍵盤已連線：${device?.name}" else "HID 連線狀態：$state") }
    }

    suspend fun sendHid(text: String, delayMs: Long = 15): String = withContext(Dispatchers.IO) {
        val target = host ?: return@withContext "HID 尚未連線"; val h = hid ?: return@withContext "HID 服務不可用"
        val keys = text.map { key(it) ?: return@withContext "包含不支援的字元；請使用完整文字模式" }
        for ((modifier, code) in keys) {
            val down = byteArrayOf(modifier.toByte(), 0, code.toByte(), 0, 0, 0, 0, 0)
            if (!h.sendReport(target, 0, down)) return@withContext "HID 傳送中斷；部分文字可能已輸入"
            var released = false
            try { kotlinx.coroutines.delay(delayMs); released = h.sendReport(target, 0, ByteArray(8)); if (!released) return@withContext "HID 釋放按鍵失敗；請斷線並檢查電腦" } finally { if (!released) try { h.sendReport(target, 0, ByteArray(8)) } catch (_: Exception) {} }
            kotlinx.coroutines.delay(delayMs)
        }
        "HID 按鍵事件已送出；請檢查電腦內容"
    }

    fun dispose() { stop(); timer.shutdownNow() }
    fun stop() { desiredHost = null; closeSocket(); try { hid?.unregisterApp(); hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) } } catch (_: Exception) {}; hid = null; host = null; registered = false }

    private fun key(c: Char): Pair<Int,Int>? {
        if (c in 'a'..'z') return 0 to (4 + (c - 'a')); if (c in 'A'..'Z') return 2 to (4 + (c - 'A')); if (c in '1'..'9') return 0 to (30 + (c - '1')); if (c == '0') return 0 to 39
        val normal = "\n\t -=[]\\;\u0027`,./"; val codes = intArrayOf(40,43,44,45,46,47,48,49,51,52,53,54,55,56); val i = normal.indexOf(c); if (i >= 0) return 0 to codes[i]
        val shifted = "!@#\$%^&*()_+{}|:\"~<>?"; val shiftedCodes = intArrayOf(30,31,32,33,34,35,36,37,38,39,45,46,47,48,49,51,52,53,54,55,56); val j = shifted.indexOf(c); return if (j >= 0) 2 to shiftedCodes[j] else null
    }

    private val descriptor = byteArrayOf(0x05,0x01,0x09,0x06,0xA1.toByte(),0x01,0x05,0x07,0x19,0xE0.toByte(),0x29,0xE7.toByte(),0x15,0x00,0x25,0x01,0x75,0x01,0x95.toByte(),0x08,0x81.toByte(),0x02,0x95.toByte(),0x01,0x75,0x08,0x81.toByte(),0x01,0x95.toByte(),0x06,0x75,0x08,0x15,0x00,0x25,0x65,0x05,0x07,0x19,0x00,0x29,0x65,0x81.toByte(),0x00,0xC0.toByte())
}

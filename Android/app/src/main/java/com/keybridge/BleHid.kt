package com.keybridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID

/**
 * Minimal HOGP keyboard server. The Android HID Device profile is unavailable
 * on some vendor ROMs, so the standard GATT profile is the next PC-compatible
 * path that does not require a Windows companion program.
 */
@SuppressLint("MissingPermission")
class BleHid(
    private val context: Context,
    private val manager: BluetoothManager,
    private val status: (String) -> Unit,
    private val connectionChanged: (BluetoothDevice?, Boolean) -> Unit,
) {
    private val adapter = manager.adapter
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var connected: BluetoothDevice? = null
    private val enabledReports = mutableSetOf<String>()
    private var protocolMode = 1
    private lateinit var inputReport: BluetoothGattCharacteristic
    private lateinit var bootInputReport: BluetoothGattCharacteristic

    fun isConnected(): Boolean = connected != null

    fun start(): Boolean {
        if (server != null) return true
        if (adapter == null || !adapter.isEnabled) {
            status("Bluetooth 未開啟，無法啟動 BLE HID")
            return false
        }
        val opened = runCatching { manager.openGattServer(context, callback) }.getOrNull()
        if (opened == null) {
            status("BLE HID GATT 服務無法開啟；此手機可能不支援")
            return false
        }
        server = opened
        if (!server!!.addService(buildService())) {
            stop()
            status("BLE HID 服務建立失敗；此手機可能不支援")
            return false
        }
        status("正在啟動 BLE HID 鍵盤…")
        return true
    }

    fun stop() {
        advertiser?.stopAdvertising(advertiseCallback)
        advertiser = null
        server?.clearServices()
        server?.close()
        server = null
        connected = null
        enabledReports.clear()
        connectionChanged(null, false)
    }

    fun sendReport(report: ByteArray): Boolean {
        val device = connected ?: return false
        if (server == null) return false
        var sent = false
        if (enabledReports.contains(reportKey(device, inputReport))) {
            val value = byteArrayOf(1) + report
            sent = notify(device, inputReport, value) || sent
        }
        if (enabledReports.contains(reportKey(device, bootInputReport))) {
            sent = notify(device, bootInputReport, report) || sent
        }
        return sent
    }

    private fun notify(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        characteristic.value = value
        return if (Build.VERSION.SDK_INT >= 33) {
            server?.notifyCharacteristicChanged(device, characteristic, false, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            server?.notifyCharacteristicChanged(device, characteristic, false) == true
        }
    }

    private fun startAdvertising() {
        val le = adapter.bluetoothLeAdvertiser
        if (le == null) {
            status("BLE 廣播無法啟動；此手機可能不支援 BLE 周邊模式")
            return
        }
        advertiser = le
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(HID_SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        le.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            status("BLE HID 鍵盤已啟用；請在 Windows 新增藍牙裝置並選擇這支手機")
        }

        override fun onStartFailure(errorCode: Int) {
            status("BLE HID 廣播失敗（代碼 $errorCode）；請保持 App 開啟")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(statusCode: Int, service: BluetoothGattService) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) startAdvertising()
            else status("BLE HID 服務註冊失敗（代碼 $statusCode）")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, statusCode: Int, newState: Int) {
            if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                connected = device
                connectionChanged(device, true)
                status("BLE HID 鍵盤已連線：${device.name ?: "Windows"}")
            } else if (connected?.address == device.address) {
                connected = null
                enabledReports.removeAll { it.startsWith("${device.address}|") }
                connectionChanged(null, false)
                status("BLE HID 已中斷；請重新連線")
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            val value = when (characteristic.uuid) {
                HID_INFO_UUID -> byteArrayOf(0x11, 0x01, 0x00)
                REPORT_MAP_UUID -> REPORT_MAP
                PROTOCOL_MODE_UUID -> byteArrayOf(protocolMode.toByte())
                INPUT_REPORT_UUID, BOOT_INPUT_REPORT_UUID -> ByteArray(8)
                BOOT_OUTPUT_REPORT_UUID -> ByteArray(1)
                else -> null
            }
            if (value == null) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            } else if (offset > value.size) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            } else {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = if (descriptor.uuid == REPORT_REFERENCE_UUID) descriptor.value ?: byteArrayOf(1, 1) else descriptor.value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            if (offset > value.size) server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            else server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }

        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            when (characteristic.uuid) {
                PROTOCOL_MODE_UUID -> protocolMode = value.firstOrNull()?.toInt() ?: 1
                BOOT_OUTPUT_REPORT_UUID -> characteristic.value = value
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (descriptor.uuid == CCC_UUID) {
                val key = reportKey(device, descriptor.characteristic)
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) enabledReports.add(key) else enabledReports.remove(key)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(HID_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val hidInfo = characteristic(HID_INFO_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        val reportMap = characteristic(REPORT_MAP_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ)
        val controlPoint = characteristic(HID_CONTROL_POINT_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        val protocol = characteristic(PROTOCOL_MODE_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
        inputReport = characteristic(INPUT_REPORT_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        inputReport.addDescriptor(reportReference(byteArrayOf(1, 1)))
        inputReport.addDescriptor(ccc())
        bootInputReport = characteristic(BOOT_INPUT_REPORT_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        bootInputReport.addDescriptor(ccc())
        val bootOutput = characteristic(BOOT_OUTPUT_REPORT_UUID, BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE)
        service.addCharacteristic(hidInfo)
        service.addCharacteristic(reportMap)
        service.addCharacteristic(controlPoint)
        service.addCharacteristic(protocol)
        service.addCharacteristic(inputReport)
        service.addCharacteristic(bootInputReport)
        service.addCharacteristic(bootOutput)
        inputReport.value = ByteArray(9)
        bootInputReport.value = ByteArray(8)
        return service
    }

    private fun characteristic(uuid: UUID, properties: Int, permissions: Int) = BluetoothGattCharacteristic(uuid, properties, permissions)
    private fun ccc() = BluetoothGattDescriptor(CCC_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE)
    private fun reportReference(value: ByteArray) = BluetoothGattDescriptor(REPORT_REFERENCE_UUID, BluetoothGattDescriptor.PERMISSION_READ).apply { this.value = value }
    private fun reportKey(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic) = "${device.address}|${characteristic.uuid}"

    companion object {
        val HID_SERVICE_UUID: UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
        private val HID_INFO_UUID: UUID = UUID.fromString("00002a4a-0000-1000-8000-00805f9b34fb")
        private val REPORT_MAP_UUID: UUID = UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb")
        private val HID_CONTROL_POINT_UUID: UUID = UUID.fromString("00002a4c-0000-1000-8000-00805f9b34fb")
        private val INPUT_REPORT_UUID: UUID = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")
        private val PROTOCOL_MODE_UUID: UUID = UUID.fromString("00002a4e-0000-1000-8000-00805f9b34fb")
        private val BOOT_INPUT_REPORT_UUID: UUID = UUID.fromString("00002a22-0000-1000-8000-00805f9b34fb")
        private val BOOT_OUTPUT_REPORT_UUID: UUID = UUID.fromString("00002a32-0000-1000-8000-00805f9b34fb")
        private val REPORT_REFERENCE_UUID: UUID = UUID.fromString("00002908-0000-1000-8000-00805f9b34fb")
        private val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val REPORT_MAP = byteArrayOf(0x05, 0x01, 0x09, 0x06, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01, 0x05, 0x07, 0x19, 0xE0.toByte(), 0x29, 0xE7.toByte(), 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x08, 0x81.toByte(), 0x02, 0x95.toByte(), 0x01, 0x75, 0x08, 0x81.toByte(), 0x01, 0x95.toByte(), 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81.toByte(), 0x00, 0xC0.toByte())
    }
}

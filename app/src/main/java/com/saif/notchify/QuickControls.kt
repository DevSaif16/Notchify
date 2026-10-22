package com.saif.notchify

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.location.GnssStatus
import android.location.LocationManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixFrame
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphMatrixObject

/**
 * State and actions behind the control centre. Nothing here runs until the panel opens: [start]
 * registers the callbacks, [stop] drops them all. Android lets an ordinary app flip location (with
 * the adb-granted secure-settings permission), the torch and the Glyph matrix directly; Wi-Fi,
 * mobile data and Bluetooth can only be switched by the system, so those tiles open the system's
 * own one-tap panel instead.
 */
class QuickControls(
    private val context: Context,
    private val torchOn: () -> Boolean,
    private val setTorch: (Boolean) -> Unit,
    private val launch: (Intent) -> Unit,
) {
    var wifiOn = false; private set
    var ssid: String? = null; private set
    var dataOn = false; private set
    var carrier: String? = null; private set
    var netType: String? = null; private set
    var btOn = false; private set
    var btDevices: List<String> = emptyList(); private set
    var locationOn = false; private set
    var cameraInUse = false; private set
    var micInUse = false; private set
    var gpsInUse = false; private set
    val glyph = GlyphLight(context) { changed() }

    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var running = false

    private val wifi = context.getSystemService(WifiManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val location = context.getSystemService(LocationManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val telephony: TelephonyManager? = context.getSystemService(TelephonyManager::class.java)?.let { tm ->
        val sub = SubscriptionManager.getDefaultDataSubscriptionId()
        if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm.createForSubscriptionId(sub) else tm
    }

    fun listen(l: () -> Unit) = listeners.add(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)
    val torch get() = torchOn()

    private fun changed() = main.post { listeners.toList().forEach { it() } }

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---- lifecycle ------------------------------------------------------------------------------

    fun start() {
        if (running) return
        running = true
        readAll()
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }, Context.RECEIVER_NOT_EXPORTED)
        runCatching {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback)
        }
        runCatching { telephony?.registerTelephonyCallback(context.mainExecutor, telephonyCallback) }
        runCatching { cameras.registerAvailabilityCallback(cameraCallback, main) }
        runCatching { audio.registerAudioRecordingCallback(recordingCallback, main) }
        micInUse = audio.activeRecordingConfigurations.isNotEmpty()
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            runCatching { location.registerGnssStatusCallback(context.mainExecutor, gnssCallback) }
        }
        changed()
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { connectivity.unregisterNetworkCallback(wifiCallback) }
        runCatching { telephony?.unregisterTelephonyCallback(telephonyCallback) }
        runCatching { cameras.unregisterAvailabilityCallback(cameraCallback) }
        runCatching { audio.unregisterAudioRecordingCallback(recordingCallback) }
        runCatching { location.unregisterGnssStatusCallback(gnssCallback) }
        unavailableCameras.clear()
    }

    private fun readAll() {
        wifiOn = wifi.isWifiEnabled
        locationOn = location.isLocationEnabled
        btOn = bluetooth?.isEnabled == true
        readBluetoothDevices()
        dataOn = runCatching { telephony?.isDataEnabled == true }.getOrDefault(false)
        carrier = telephony?.simOperatorName?.takeIf { it.isNotBlank() } ?: telephony?.networkOperatorName?.takeIf { it.isNotBlank() }
        if (netType == null) netType = runCatching { typeName(telephony?.dataNetworkType ?: 0, 0) }.getOrNull()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            readAll()
            changed()
        }
    }

    private val wifiCallback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val name = (caps.transportInfo as? WifiInfo)?.ssid?.trim('"')
            ssid = name?.takeIf { it.isNotBlank() && it != WifiManager.UNKNOWN_SSID.trim('<', '>') && !it.startsWith("<") } ?: "Connected"
            changed()
        }

        override fun onLost(network: Network) {
            ssid = null
            changed()
        }
    }

    private val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
        override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
            netType = typeName(info.networkType, info.overrideNetworkType)
            changed()
        }
    }

    private fun typeName(type: Int, override: Int): String? = when (override) {
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "4G+"
        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "4G+"
        else -> when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
            else -> null
        }
    }

    private val unavailableCameras = HashSet<String>()
    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            unavailableCameras += cameraId
            cameraInUse = true
            changed()
        }

        override fun onCameraAvailable(cameraId: String) {
            unavailableCameras -= cameraId
            cameraInUse = unavailableCameras.isNotEmpty()
            changed()
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            micInUse = configs.isNotEmpty()
            changed()
        }
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onStarted() {
            gpsInUse = true
            changed()
        }

        override fun onStopped() {
            gpsInUse = false
            changed()
        }
    }

    /** Names of connected audio devices, read through the profile proxies (needs Nearby devices). */
    private fun readBluetoothDevices() {
        val adapter = bluetooth ?: return
        if (!adapter.isEnabled || !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            btDevices = emptyList()
            return
        }
        val found = LinkedHashSet<String>()
        for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO)) {
            runCatching {
                adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        runCatching { proxy.connectedDevices.mapNotNullTo(found) { it.alias ?: it.name } }
                        btDevices = found.toList()
                        adapter.closeProfileProxy(p, proxy)
                        changed()
                    }

                    override fun onServiceDisconnected(p: Int) = Unit
                }, profile)
            }
        }
    }

    // ---- actions --------------------------------------------------------------------------------

    fun toggleWifi() {
        @Suppress("DEPRECATION")
        val done = runCatching { wifi.setWifiEnabled(!wifiOn) }.getOrDefault(false)
        if (!done) launch(Intent(Settings.Panel.ACTION_WIFI))
    }

    fun toggleData() = launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))

    fun toggleBluetooth() {
        if (!btOn && granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
    }

    fun toggleLocation() {
        val ok = HeadsUp.granted(context) && runCatching {
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE,
                if (locationOn) Settings.Secure.LOCATION_MODE_OFF else Settings.Secure.LOCATION_MODE_HIGH_ACCURACY)
        }.getOrDefault(false)
        if (!ok) launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        main.postDelayed({
            readAll()
            changed()
        }, 300)
    }

    fun toggleTorch() {
        setTorch(!torchOn())
        main.postDelayed({ changed() }, 150)
    }

    fun openSettings(what: String) = launch(
        Intent(
            when (what) {
                "wifi" -> Settings.ACTION_WIFI_SETTINGS
                "data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
                "bt" -> Settings.ACTION_BLUETOOTH_SETTINGS
                else -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            },
        ),
    )
}

/**
 * The Glyph matrix on the back as a flashlight: every LED at full brightness, through Nothing's
 * Glyph Matrix SDK. Without an API key from Nothing the SDK only answers in debug mode, which the
 * app switches on itself through the adb-granted secure-settings permission.
 */
class GlyphLight(private val context: Context, private val onChange: () -> Unit) {
    var on = false
        private set
    private var manager: GlyphMatrixManager? = null

    val available: Boolean
        get() = runCatching {
            context.packageManager.getPackageInfo("com.nothing.thirdparty", 0)
            Common.is25111p() || Common.is23112()
        }.getOrDefault(false)

    fun toggle() = if (on) off() else light()

    private fun light() {
        runCatching { Settings.Global.putInt(context.contentResolver, DEBUG_SETTING, 1) }
        val m = manager ?: GlyphMatrixManager.getInstance(context.applicationContext).also { manager = it }
        m.init(object : GlyphMatrixManager.Callback {
            override fun onServiceConnected(name: android.content.ComponentName?) {
                runCatching {
                    m.register(if (Common.is23112()) Glyph.DEVICE_23112 else Glyph.DEVICE_25111p)
                    runCatching { m.setGlyphMatrixTimeout(false) }
                    m.setAppMatrixFrame(fullFrame())
                    on = true
                }
                onChange()
            }

            override fun onServiceDisconnected(name: android.content.ComponentName?) {
                on = false
                onChange()
            }
        })
    }

    fun off() {
        val m = manager
        runCatching { m?.closeAppMatrix() }
        runCatching { m?.turnOff() }
        runCatching { m?.unInit() }
        manager = null
        if (on) {
            on = false
            onChange()
        }
    }

    private fun fullFrame(): IntArray {
        val len = Common.getDeviceMatrixLength().takeIf { it > 0 } ?: 13
        val white = Bitmap.createBitmap(len, len, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val all = GlyphMatrixObject.Builder()
            .setImageSource(white).setScale(100).setOrientation(0).setPosition(0, 0).setBrightness(255).build()
        return GlyphMatrixFrame.Builder().addTop(all).build(context).render()
    }

    companion object {
        private const val DEBUG_SETTING = "nt_glyph_interface_debug_enable"
    }
}

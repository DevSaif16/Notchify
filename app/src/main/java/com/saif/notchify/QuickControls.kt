package com.saif.notchify

import android.Manifest
import android.app.NotificationManager
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
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
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
 * registers the callbacks, [stop] drops them all.
 *
 * Switched in place: mobile data (secure settings), location (secure settings), flashlight,
 * Glyph light, Do Not Disturb (as the notification listener), brightness, media volume.
 * Wi-Fi and Bluetooth: Android only lets the system switch those for apps built for current
 * Android, so their tiles open the system's own one-tap popups.
 */
class QuickControls(
    private val context: Context,
    private val torchOn: () -> Boolean,
    private val setTorch: (Boolean) -> Unit,
    private val screenshot: () -> Unit,
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
    var dndOn = false; private set
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
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val dataSub = SubscriptionManager.getDefaultDataSubscriptionId()
    private val telephony: TelephonyManager? = context.getSystemService(TelephonyManager::class.java)?.let { tm ->
        if (dataSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) tm.createForSubscriptionId(dataSub) else tm
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
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
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
        dndOn = notifications.currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
        readBluetoothDevices()
        dataOn = Settings.Global.getInt(context.contentResolver, "mobile_data$dataSub", -1).takeIf { it >= 0 }?.let { it == 1 }
            ?: runCatching { telephony?.isDataEnabled == true }.getOrDefault(false)
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
            ssid = name?.takeIf { it.isNotBlank() && !it.startsWith("<") } ?: "Connected"
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

    private fun refreshSoon() = main.postDelayed({
        readAll()
        changed()
    }, 350)

    // ---- tap: toggle ----------------------------------------------------------------------------

    /** Opens the system's Wi-Fi popup: the one place Android allows Wi-Fi to be switched. */
    fun tapWifi() = launch(Intent(Settings.Panel.ACTION_WIFI))

    /** Mobile data flips in place, through the setting the phone service itself listens to. */
    fun tapData() {
        val ok = HeadsUp.granted(context) && dataSub != SubscriptionManager.INVALID_SUBSCRIPTION_ID && runCatching {
            val v = if (dataOn) 0 else 1
            Settings.Global.putInt(context.contentResolver, "mobile_data$dataSub", v)
            Settings.Global.putInt(context.contentResolver, "mobile_data", v)
        }.getOrDefault(false)
        if (ok) {
            dataOn = !dataOn
            changed()
            refreshSoon()
        } else {
            launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
        }
    }

    /** Turning on asks once in a system dialog; turning off needs the system Bluetooth page. */
    fun tapBluetooth() = launch(
        if (!btOn) Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE) else Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
    )

    fun tapLocation() {
        val ok = HeadsUp.granted(context) && runCatching {
            Settings.Secure.putInt(
                context.contentResolver, Settings.Secure.LOCATION_MODE,
                if (locationOn) Settings.Secure.LOCATION_MODE_OFF else Settings.Secure.LOCATION_MODE_HIGH_ACCURACY,
            )
        }.getOrDefault(false)
        if (ok) {
            locationOn = !locationOn
            changed()
            refreshSoon()
        } else {
            launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    fun tapTorch() {
        setTorch(!torchOn())
        main.postDelayed({ changed() }, 150)
    }

    fun tapDnd() {
        val listener = NotificationService.instance
        if (listener == null) {
            launch(Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS))
            return
        }
        listener.requestInterruptionFilter(
            if (dndOn) NotificationListenerService.INTERRUPTION_FILTER_ALL else NotificationListenerService.INTERRUPTION_FILTER_PRIORITY,
        )
        dndOn = !dndOn
        changed()
    }

    fun tapScreenshot() = screenshot()

    // ---- long press: the system popup / page ----------------------------------------------------

    fun more(what: String) = launch(
        when (what) {
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "data" -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
            "bt" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "dnd" -> Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)
            else -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        },
    )

    // ---- sliders --------------------------------------------------------------------------------

    val brightness: Float
        get() = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f

    /** Needs "Modify system settings"; the first drag without it opens that page instead. */
    fun setBrightness(f: Float): Boolean {
        if (!Settings.System.canWrite(context)) {
            launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            return false
        }
        return runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (f.coerceIn(0.01f, 1f) * 255).toInt())
        }.getOrDefault(false)
    }

    val volume: Float
        get() = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    fun setVolume(f: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (f.coerceIn(0f, 1f) * max).toInt(), 0) }
    }
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

    val available: Boolean by lazy {
        runCatching {
            context.packageManager.getPackageInfo("com.nothing.thirdparty", 0)
            Common.is25111p() || Common.is23112()
        }.getOrDefault(false)
    }

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

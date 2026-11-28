package com.novex.jarvis

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/** JavaScript bridge. Only callable by pages from HOME (see MainActivity). */
class PhoneBridge(private val ctx: Context, private val web: WebView) {
    companion object {
        /** false = Jarvis only opens the dialer / SMS composer and YOU tap send (safer). true = calls and sends directly. */
        const val AUTO_SEND = false
    }

    @Volatile var trusted = false
    private val main = Handler(Looper.getMainLooper())
    private val acc get() = JarvisAccessibilityService.inst

    private fun js(code: String) { main.post { web.evaluateJavascript(code, null) } }
    private fun launch(i: Intent) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i) }
    private fun needAcc() = "ERROR: Turn on the NOVEX Jarvis accessibility service first (tap ENABLE CONTROL)."

    @JavascriptInterface fun available(): Boolean = trusted
    @JavascriptInterface fun accessibilityOn(): Boolean = trusted && acc != null
    @JavascriptInterface fun openAccessibilitySettings() { if (trusted) launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

    @JavascriptInterface
    fun run(action: String, arg: String): String {
        if (!trusted) return "ERROR: untrusted page"
        return try { exec(action, arg) } catch (e: Exception) { "ERROR: " + (e.message ?: e.javaClass.simpleName) }
    }

    @JavascriptInterface
    fun listen(lang: String) {
        if (!trusted) return
        main.post {
            val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
            fun done(t: String) { js("window.onNativeSpeech&&window.onNativeSpeech(" + JSONObject.quote(t) + ")"); sr.destroy() }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) { done(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: "") }
                override fun onError(e: Int) { done("") }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            sr.startListening(i)
        }
    }

    private fun global(code: Int, ok: String): String {
        val s = acc ?: return needAcc()
        return if (s.performGlobalAction(code)) "OK: $ok" else "ERROR: Android refused that action"
    }

    private fun media(code: Int, ok: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "OK: $ok"
    }

    private fun exec(a: String, arg: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (a) {
            "torch_on", "torch_off" -> {
                val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                    ?: return "ERROR: No flashlight found"
                cm.setTorchMode(id, a == "torch_on")
                return "OK: Flashlight " + (if (a == "torch_on") "on" else "off")
            }
            "vol_up" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return "OK: Volume up" }
            "vol_down" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return "OK: Volume down" }
            "mute" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI); return "OK: Muted" }
            "unmute" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI); return "OK: Unmuted" }
            "set_volume" -> {
                val p = arg.toIntOrNull()?.coerceIn(0, 100) ?: return "ERROR: I need a number"
                val mx = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, mx * p / 100, AudioManager.FLAG_SHOW_UI)
                return "OK: Volume $p percent"
            }
            "bright_up", "bright_down", "set_brightness" -> {
                if (!Settings.System.canWrite(ctx)) {
                    launch(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).setData(Uri.parse("package:" + ctx.packageName)))
                    return "ERROR: Allow 'Modify system settings' for Jarvis, then repeat the command."
                }
                val cr = ctx.contentResolver
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
                val nv = when (a) {
                    "bright_up" -> cur + 40
                    "bright_down" -> cur - 40
                    else -> (arg.toIntOrNull() ?: 50).coerceIn(0, 100) * 255 / 100
                }.coerceIn(5, 255)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, nv)
                return "OK: Brightness " + (nv * 100 / 255) + " percent"
            }
            "dnd_on", "dnd_off" -> {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) {
                    launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                    return "ERROR: Allow Do Not Disturb access for Jarvis, then repeat the command."
                }
                nm.setInterruptionFilter(if (a == "dnd_on") NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL)
                return "OK: Do not disturb " + (if (a == "dnd_on") "on" else "off")
            }
            "wifi_panel" -> { launch(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)); return "OK: Opened the Wi-Fi panel. Android does not let apps switch Wi-Fi directly." }
            "bt_panel" -> { launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return "OK: Opened Bluetooth settings." }
            "open_app" -> {
                val pm = ctx.packageManager
                val q = arg.trim().lowercase()
                val ri = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                val best = ri.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
                    ?: ri.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
                    ?: return "ERROR: not found: $arg"
                val li = pm.getLaunchIntentForPackage(best.activityInfo.packageName) ?: return "ERROR: cannot open that app"
                launch(li)
                return "OK: Opening " + best.loadLabel(pm)
            }
            "call" -> {
                val n = arg.replace(Regex("[^\\d+]"), "")
                if (AUTO_SEND && ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                    launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:$n"))); return "OK: Calling $n"
                }
                launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))); return "OK: Dialer opened for $n. Tap the green button to call."
            }
            "sms" -> {
                val p = arg.split("|", limit = 2)
                if (p.size < 2) return "ERROR: I need a number and a message"
                val n = p[0].replace(Regex("[^\\d+]"), "")
                if (AUTO_SEND && ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
                    val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
                    sm.sendMultipartTextMessage(n, null, sm.divideMessage(p[1]), null, null)
                    return "OK: Message sent to $n"
                }
                launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$n")).putExtra("sms_body", p[1]))
                return "OK: Message ready for $n. Tap send."
            }
            "alarm" -> {
                val p = arg.split(":")
                val h = p[0].toIntOrNull() ?: return "ERROR: bad time"
                val m = p.getOrNull(1)?.toIntOrNull() ?: 0
                launch(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
                return "OK: Alarm set for %02d:%02d".format(h, m)
            }
            "battery" -> {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                return "OK: Battery is at " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) + " percent"
            }
            "media_next" -> return media(KeyEvent.KEYCODE_MEDIA_NEXT, "Next track")
            "media_prev" -> return media(KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Previous track")
            "media_pause" -> return media(KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused")
            "media_play" -> return media(KeyEvent.KEYCODE_MEDIA_PLAY, "Playing")
            "home" -> return global(AccessibilityService.GLOBAL_ACTION_HOME, "Home")
            "back" -> return global(AccessibilityService.GLOBAL_ACTION_BACK, "Back")
            "recents" -> return global(AccessibilityService.GLOBAL_ACTION_RECENTS, "Recent apps")
            "notifications" -> return global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Notifications opened")
            "quick_settings" -> return global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Quick settings opened")
            "lock" -> return global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, "Screen locked")
            "screenshot" -> return global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, "Screenshot taken")
            "tap_text" -> { val s = acc ?: return needAcc(); return if (s.tapText(arg)) "OK: Tapped $arg" else "ERROR: I could not find '$arg' on the screen" }
            "type_text" -> { val s = acc ?: return needAcc(); return if (s.typeText(arg)) "OK: Typed it" else "ERROR: No text box is selected" }
            "scroll_down" -> { val s = acc ?: return needAcc(); return if (s.scroll(true)) "OK: Scrolled down" else "ERROR: Nothing to scroll" }
            "scroll_up" -> { val s = acc ?: return needAcc(); return if (s.scroll(false)) "OK: Scrolled up" else "ERROR: Nothing to scroll" }
            "read_screen" -> { val s = acc ?: return needAcc(); val t = s.screenText(); return if (t.isBlank()) "ERROR: Nothing readable on screen" else "OK: On screen: $t" }
        }
        return "ERROR: Unknown action $a"
    }
}

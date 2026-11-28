package com.novex.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.*

const val HOME = "https://navedpathan7456-dotcom.github.io/Novex-jarvis/"
const val HOST = "navedpathan7456-dotcom.github.io"

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var bridge: PhoneBridge
    private var fileCb: ValueCallback<Array<Uri>>? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        web = WebView(this)
        setContentView(web)
        WebView.setWebContentsDebuggingEnabled(false)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
        }
        bridge = PhoneBridge(this, web)
        web.addJavascriptInterface(bridge, "NovexPhone")
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, url: String, f: android.graphics.Bitmap?) {
                bridge.trusted = url.startsWith(HOME)
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                if (r.url.host == HOST) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (_: Exception) {}
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(r: PermissionRequest) {
                runOnUiThread { if (r.origin.host == HOST) r.grant(r.resources) else r.deny() }
            }
            override fun onGeolocationPermissionsShowPrompt(o: String, cb: GeolocationPermissions.Callback) {
                cb.invoke(o, o.contains(HOST), false)
            }
            override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                return try { startActivityForResult(p.createIntent(), 9); true } catch (e: Exception) { fileCb = null; false }
            }
        }
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        if (PhoneBridge.AUTO_SEND) { perms.add(Manifest.permission.CALL_PHONE); perms.add(Manifest.permission.SEND_SMS) }
        requestPermissions(perms.toTypedArray(), 1)
        web.loadUrl(HOME)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        if (req == 9) {
            fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data))
            fileCb = null
        }
        super.onActivityResult(req, res, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}

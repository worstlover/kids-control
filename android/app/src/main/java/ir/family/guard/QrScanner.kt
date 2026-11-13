package ir.family.guard

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live QR scanning inside the app, like the phone's own camera: point it at the QR and it opens by itself.
 * Camera2 + the bundled ZXing reader, no extra libraries and no internet. If the camera cannot be used,
 * the old "take a photo of the QR" way is offered.
 */
object QrScan {
    internal var pending: ((String) -> Unit)? = null
    const val REQ_CAMERA = 12
}

fun MainActivity.scanQr(onUrl: (String) -> Unit) {
    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { openScanner(onUrl); return }
    QrScan.pending = onUrl
    prefs.graceUntil = System.currentTimeMillis() + 90_000     // the system permission screen must not be blocked
    requestPermissions(arrayOf(Manifest.permission.CAMERA), QrScan.REQ_CAMERA)
}

/** Called from MainActivity.onRequestPermissionsResult. */
fun MainActivity.onCameraPermission(granted: Boolean) {
    prefs.graceUntil = 0
    val cb = QrScan.pending; QrScan.pending = null
    if (cb == null) return
    if (granted) openScanner(cb)
    else { toast("بدون اجازه‌ی دوربین، از QR عکس می‌گیریم"); takePhoto("qr", "") }
}

@SuppressLint("MissingPermission")
private fun MainActivity.openScanner(onUrl: (String) -> Unit) {
    val act = this
    val dlg = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    val tex = TextureView(this)
    root.addView(tex, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    // a square frame in the middle, where the QR should be
    val box = View(this).apply {
        background = GradientDrawable().apply { setStroke(ui.dp(4), Color.WHITE); cornerRadius = ui.dp(18).toFloat(); setColor(Color.TRANSPARENT) }
    }
    val side = (resources.displayMetrics.widthPixels * 0.7).toInt()
    root.addView(box, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
    val hint = TextView(this).apply {
        text = "QR کتاب را داخل کادر بگیر 🔳\nخودش باز می‌شود"; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }
    root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = ui.dp(70) })
    val bar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(28)) }
    root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

    val camThread = HandlerThread("qr-cam").apply { start() }
    val camHandler = Handler(camThread.looper)
    val decThread = HandlerThread("qr-decode").apply { start() }
    val decHandler = Handler(decThread.looper)
    val main = Handler(Looper.getMainLooper())
    val busy = AtomicBoolean(false)
    val done = AtomicBoolean(false)
    var device: CameraDevice? = null
    var session: CameraCaptureSession? = null
    var reader: ImageReader? = null
    var request: CaptureRequest.Builder? = null
    var torch = false

    fun closeCamera() {
        try { session?.close() } catch (e: Exception) {}
        try { device?.close() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        session = null; device = null; reader = null
    }
    fun stopAll() {
        camHandler.post { closeCamera() }
        camThread.quitSafely(); decThread.quitSafely()
    }
    fun fallbackToPhoto(msg: String) {
        if (!done.compareAndSet(false, true)) return
        main.post { try { dlg.dismiss() } catch (e: Exception) {}; toast(msg); takePhoto("qr", "") }
    }

    bar.addView(ui.pill("🔦 چراغ", Pal.MUTED) {
        val rq = request ?: return@pill
        torch = !torch
        camHandler.post {
            try {
                rq.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                session?.setRepeatingRequest(rq.build(), null, camHandler)
            } catch (e: Exception) {}
        }
    })
    bar.addView(ui.pill("📷 با عکس گرفتن", Pal.BLUE) { if (done.compareAndSet(false, true)) { dlg.dismiss(); takePhoto("qr", "") } })
    bar.addView(ui.pill("✕ بستن", Pal.RED) { done.set(true); dlg.dismiss() })

    fun found(text: String) {
        if (!done.compareAndSet(false, true)) return
        main.post {
            try { (getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.vibrate(android.os.VibrationEffect.createOneShot(80, 120)) } catch (e: Exception) {}
            try { dlg.dismiss() } catch (e: Exception) {}
            onUrl(text.trim())
        }
    }

    fun start(st: SurfaceTexture) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: cm.cameraIdList.firstOrNull()
        } catch (e: Exception) { null } ?: return fallbackToPhoto("دوربین پیدا نشد؛ از QR عکس بگیر")
        val map = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        // ~720p, wide: enough detail for a small QR, small enough to read many frames a second
        val size = sizes.filter { it.width <= 1920 && it.height <= 1080 && it.width > it.height }
            .minByOrNull { Math.abs(it.width * it.height - 1280 * 720) + if (it.width * 9 == it.height * 16) 0 else 200_000 } ?: Size(1280, 720)
        // keep the preview's shape (portrait screen shows the landscape frame turned)
        main.post {
            val w = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            tex.layoutParams = FrameLayout.LayoutParams(w, (w.toLong() * size.width / size.height).toInt(), Gravity.CENTER)
        }
        st.setDefaultBufferSize(size.width, size.height)
        val rd = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = rd
        rd.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            if (done.get() || !busy.compareAndSet(false, true)) { img.close(); return@setOnImageAvailableListener }
            // copy the brightness plane (rows can have padding), then let go of the frame at once
            val w = img.width; val h = img.height
            val plane = img.planes[0]; val buf = plane.buffer; val rs = plane.rowStride
            val y = ByteArray(w * h)
            try {
                if (rs == w) buf.get(y, 0, minOf(y.size, buf.remaining()))
                else for (row in 0 until h) { buf.position(row * rs); buf.get(y, row * w, w) }
            } catch (e: Exception) { img.close(); busy.set(false); return@setOnImageAvailableListener }
            img.close()
            decHandler.post {
                val text = try { Qr.decodeY(y, w, h) } catch (e: Exception) { null }
                busy.set(false)
                if (!text.isNullOrBlank()) found(text)
            }
        }, camHandler)
        try {
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    if (done.get()) { d.close(); return }
                    device = d
                    val preview = Surface(st)
                    try {
                        val rq = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview); addTarget(rd.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }
                        request = rq
                        @Suppress("DEPRECATION")
                        d.createCaptureSession(listOf(preview, rd.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try { s.setRepeatingRequest(rq.build(), null, camHandler) } catch (e: Exception) { fallbackToPhoto("دوربین باز نشد؛ از QR عکس بگیر") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { fallbackToPhoto("دوربین باز نشد؛ از QR عکس بگیر") }
                        }, camHandler)
                    } catch (e: Exception) { fallbackToPhoto("دوربین باز نشد؛ از QR عکس بگیر") }
                }
                override fun onDisconnected(d: CameraDevice) { try { d.close() } catch (e: Exception) {}; device = null }
                override fun onError(d: CameraDevice, error: Int) { try { d.close() } catch (e: Exception) {}; device = null; fallbackToPhoto("دوربین در دسترس نیست؛ از QR عکس بگیر") }
            }, camHandler)
        } catch (e: Exception) { fallbackToPhoto("دوربین در دسترس نیست؛ از QR عکس بگیر") }
    }

    tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { camHandler.post { start(st) } }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { camHandler.post { closeCamera() }; return true }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }
    // after a while without a hit, remind the kid of the other ways
    main.postDelayed({ if (!done.get() && dlg.isShowing) hint.text = "نزدیک‌تر یا دورتر بگیر، نور کافی باشد 💡\nاگر نشد: «با عکس گرفتن»" }, 12_000)
    dlg.setContentView(root)
    dlg.setOnDismissListener { done.set(true); stopAll() }
    if (!act.isFinishing) dlg.show()
}

package com.passportphoto.camera

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        const val PORT = 8766
        const val REQ_PHOTO = 1
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var autoBox: CheckBox
    private val io = Executors.newCachedThreadPool()

    private var photoUri: Uri? = null
    private var host = ""
    private var token = ""
    private var started = false

    @Volatile private var busy = false
    @Volatile private var connected = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("pb", Context.MODE_PRIVATE)
        host = prefs.getString("host", "") ?: ""
        token = prefs.getString("token", "") ?: ""
        savedInstanceState?.getString("uri")?.let { photoUri = Uri.parse(it) }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setBackgroundColor(Color.parseColor("#0f172a"))
        root.setPadding(dp(24), dp(56), dp(24), dp(24))

        val title = TextView(this)
        title.text = "Passport Camera"
        title.textSize = 24f
        title.setTextColor(Color.WHITE)
        title.gravity = Gravity.CENTER
        root.addView(title)

        status = TextView(this)
        status.textSize = 15f
        status.gravity = Gravity.CENTER
        status.setTextColor(Color.parseColor("#9fb3cf"))
        status.setPadding(0, dp(14), 0, dp(28))
        root.addView(status)

        val shot = Button(this)
        shot.text = "Photo khinchein"
        shot.textSize = 20f
        shot.setTextColor(Color.WHITE)
        shot.setBackgroundColor(Color.parseColor("#2269e8"))
        shot.setOnClickListener { onShot() }
        root.addView(shot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88)))

        val find = Button(this)
        find.text = "PC dobara dhoondho"
        find.setOnClickListener { connected = false; connect(null) }
        val lp2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        lp2.topMargin = dp(18)
        root.addView(find, lp2)

        val manual = Button(this)
        manual.text = "IP / link khud daalein"
        manual.setOnClickListener { manualDialog() }
        val lp3 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        lp3.topMargin = dp(8)
        root.addView(manual, lp3)

        autoBox = CheckBox(this)
        autoBox.text = "Khulte hi camera chalu karein"
        autoBox.setTextColor(Color.WHITE)
        autoBox.isChecked = prefs.getBoolean("auto", true)
        autoBox.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("auto", on).apply() }
        val lp4 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp4.topMargin = dp(20)
        root.addView(autoBox, lp4)

        setContentView(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        photoUri?.let { outState.putString("uri", it.toString()) }
    }

    override fun onResume() {
        super.onResume()
        if (!started) {
            started = true
            if (autoBox.isChecked) connect { takePhoto() } else connect(null)
        } else if (!connected && !busy) {
            connect(null)
        }
    }

    private fun say(text: String, color: String = "#9fb3cf") {
        runOnUiThread {
            status.text = text
            status.setTextColor(Color.parseColor(color))
        }
    }

    private fun onShot() {
        if (connected) takePhoto() else connect { takePhoto() }
    }

    // ---------------- finding the PC ----------------

    private fun connect(after: (() -> Unit)?) {
        if (busy) return
        busy = true
        say("PC dhoondh raha hu...", "#7dd3fc")
        io.execute {
            try {
                var h = host
                if (h.isEmpty() || !hello(h)) {
                    h = scan() ?: ""
                }
                if (h.isEmpty()) {
                    say("PC nahi mila. Check karein: PC par bridge chalu hai (tool mein Mobile icon dabayein) aur phone-PC ek hi WiFi par hain.", "#fb7185")
                    return@execute
                }
                host = h
                prefs.edit().putString("host", h).apply()
                if (token.isEmpty()) {
                    val t = pair(h)
                    if (t == null) {
                        say("Pehli baar jodne ke liye PC par tool mein Mobile icon dabayein, phir yahan 'PC dobara dhoondho' dabayein.", "#fbbf24")
                        return@execute
                    }
                    token = t
                    prefs.edit().putString("token", t).apply()
                }
                connected = true
                say("\u2713 PC se juda hua ($h)", "#4ade80")
                if (after != null) runOnUiThread { after() }
            } finally {
                busy = false
            }
        }
    }

    private fun localIpv4(): String? {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        for (la in lp.linkAddresses) {
            val a = la.address
            if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
        }
        return null
    }

    private fun portOpen(h: String): Boolean {
        return try {
            Socket().use { it.connect(InetSocketAddress(h, PORT), 350) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun hello(h: String): Boolean {
        return try {
            val c = URL("http://$h:$PORT/hello").openConnection() as HttpURLConnection
            c.connectTimeout = 700
            c.readTimeout = 1200
            val ok = c.responseCode == 200 && c.inputStream.bufferedReader().use { it.readText() }.contains("phonebridge")
            c.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    private fun scan(): String? {
        val ip = localIpv4() ?: return null
        val prefix = ip.substringBeforeLast('.')
        val pool = Executors.newFixedThreadPool(48)
        try {
            val tasks = (1..254).map { i ->
                Callable<String?> {
                    val cand = "$prefix.$i"
                    if (cand != ip && portOpen(cand) && hello(cand)) cand else null
                }
            }
            for (f in pool.invokeAll(tasks)) {
                val r = f.get()
                if (r != null) return r
            }
            return null
        } finally {
            pool.shutdownNow()
        }
    }

    private fun pair(h: String): String? {
        return try {
            val c = URL("http://$h:$PORT/pair").openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 3000
            if (c.responseCode != 200) {
                c.disconnect()
                null
            } else {
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                Regex("\"token\"\\s*:\\s*\"([0-9a-f]+)\"").find(body)?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun manualDialog() {
        val et = EditText(this)
        et.hint = "192.168.1.5  ya poora link"
        et.setSingleLine()
        AlertDialog.Builder(this)
            .setTitle("PC ka IP ya link")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val t = et.text.toString().trim()
                val h = t.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
                val tk = Regex("[?&]t=([0-9a-f]+)").find(t)?.groupValues?.get(1)
                if (h.isNotEmpty()) {
                    host = h
                    prefs.edit().putString("host", h).apply()
                    if (tk != null) {
                        token = tk
                        prefs.edit().putString("token", tk).apply()
                    }
                    connected = false
                    connect(null)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------- camera ----------------

    private fun takePhoto() {
        val v = ContentValues()
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "passport_" + System.currentTimeMillis() + ".jpg")
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PassportCamera")
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
        if (uri == null) {
            say("Photo ke liye jagah nahi bani.", "#fb7185")
            return
        }
        photoUri = uri
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        i.putExtra(MediaStore.EXTRA_OUTPUT, uri)
        try {
            startActivityForResult(i, REQ_PHOTO)
        } catch (e: Exception) {
            say("Camera app nahi khul paya.", "#fb7185")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PHOTO) return
        val u = photoUri ?: return
        if (resultCode == Activity.RESULT_OK) {
            upload(u)
        } else {
            try { contentResolver.delete(u, null, null) } catch (e: Exception) { }
        }
    }

    // ---------------- upload ----------------

    private fun upload(uri: Uri) {
        say("Photo bhej raha hu...", "#7dd3fc")
        io.execute {
            try {
                val bytes = prepare(uri)
                var code = post(bytes)
                if (code == 403) {
                    val t = pair(host)
                    if (t != null) {
                        token = t
                        prefs.edit().putString("token", t).apply()
                        code = post(bytes)
                    }
                }
                if (code == 200) {
                    say("\u2713 Photo PC par pahunch gayi! Dusri photo ke liye button dabayein.", "#4ade80")
                    try { contentResolver.delete(uri, null, null) } catch (e: Exception) { }
                } else if (code == 403) {
                    say("Link badal gaya hai. PC par tool mein Mobile icon dabayein, phir photo dobara bhejein.", "#fbbf24")
                } else {
                    say("Bhej nahi paya (code $code)", "#fb7185")
                }
            } catch (e: Exception) {
                connected = false
                say("PC se connection toot gaya. 'PC dobara dhoondho' dabayein. (Photo gallery mein surakshit hai)", "#fb7185")
            }
        }
    }

    private fun prepare(uri: Uri): ByteArray {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 3200) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        var bmp: Bitmap = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }!!
        val orient = contentResolver.openInputStream(uri)!!.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val m = Matrix()
        when (orient) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(270f)
        }
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val big = maxOf(bmp.width, bmp.height)
        if (big > 3200) {
            val k = 3200f / big
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 93, out)
        return out.toByteArray()
    }

    private fun post(bytes: ByteArray): Int {
        val c = URL("http://$host:$PORT/upload?t=$token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 3000
        c.readTimeout = 30000
        c.setRequestProperty("Content-Type", "image/jpeg")
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        val code = c.responseCode
        c.disconnect()
        return code
    }
}

package com.vibe.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Collections

/**
 * A deliberately tiny, fast TV launcher.
 *
 *  - Favourites row at the top. Hold OK on a grid app to pin it.
 *    Hold OK on a favourite to "grab" it, then Left/Right to reorder, OK to drop.
 *  - 6-wide grid of all apps, with a clear focus highlight.
 *  - Auto-rotating nature wallpaper from Bing's daily image feed (no API key).
 */
class MainActivity : Activity() {

    data class AppInfo(val pkg: String, val label: String, val icon: Drawable, val launch: Intent)

    private var allApps: List<AppInfo> = emptyList()
    private lateinit var adapter: AppAdapter

    private lateinit var wallpaperView: ImageView
    private lateinit var favRow: LinearLayout
    private lateinit var favHint: TextView

    private val favOrder = mutableListOf<String>()   // ordered favourite package names
    private var movingPkg: String? = null            // the favourite currently "grabbed"

    private val prefs by lazy { getSharedPreferences("vibe", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        allApps = loadApps()
        favOrder.clear(); favOrder.addAll(loadFavOrder())

        wallpaperView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
        }
        val scrim = View(this).apply { setBackgroundColor(0x99000000.toInt()) }

        val favLabel = sectionLabel("Favourites")
        favRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        favHint = TextView(this).apply {
            text = "Hold OK on an app below to pin it here"
            setTextColor(0xFFAAAAAA.toInt())
            textSize = 14f
        }
        val favScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(favRow)
        }

        val grid = GridView(this).apply {
            numColumns = 6
            verticalSpacing = dp(20)
            horizontalSpacing = dp(16)
            isVerticalScrollBarEnabled = false
            selector = focusBackground()
            setDrawSelectorOnTop(true)
            adapter = AppAdapter().also { this@MainActivity.adapter = it }
            setOnItemClickListener { _, _, pos, _ -> launch(allApps[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> toggleFavourite(allApps[pos]); true }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            val p = dp(32)
            setPadding(p, p, p, p)
            addView(favLabel)
            addView(favHint, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) })
            addView(favScroller, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) })
            addView(divider(), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).apply { bottomMargin = dp(24) })
            addView(grid, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        val root = FrameLayout(this).apply {
            addView(wallpaperView, FrameLayout.LayoutParams(-1, -1))
            addView(scrim, FrameLayout.LayoutParams(-1, -1))
            addView(content, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)

        buildFavouritesRow()
        showCachedWallpaper()
        refreshWallpaperAsync()

        grid.requestFocus()
    }

    // ---------------- Favourites ----------------

    private fun loadFavOrder(): MutableList<String> {
        val raw = prefs.getString("favs_order", "") ?: ""
        return if (raw.isEmpty()) mutableListOf() else raw.split("\n").toMutableList()
    }

    private fun saveFavOrder() {
        prefs.edit().putString("favs_order", favOrder.joinToString("\n")).apply()
    }

    private fun toggleFavourite(app: AppInfo) {
        if (favOrder.contains(app.pkg)) {
            favOrder.remove(app.pkg)
            if (movingPkg == app.pkg) movingPkg = null
            Toast.makeText(this, "Unpinned ${app.label}", Toast.LENGTH_SHORT).show()
        } else {
            favOrder.add(app.pkg)
            Toast.makeText(this, "Pinned ${app.label}", Toast.LENGTH_SHORT).show()
        }
        saveFavOrder()
        buildFavouritesRow()
    }

    private fun startMoving(app: AppInfo) {
        movingPkg = app.pkg
        Toast.makeText(this, "Moving ${app.label} — Left/Right, OK to drop", Toast.LENGTH_SHORT).show()
        buildFavouritesRow()
    }

    private fun stopMoving() {
        movingPkg = null
        buildFavouritesRow()
    }

    private fun moveFav(pkg: String, dir: Int) {
        val i = favOrder.indexOf(pkg)
        if (i < 0) return
        val j = i + dir
        if (j < 0 || j >= favOrder.size) return
        Collections.swap(favOrder, i, j)
        saveFavOrder()
        buildFavouritesRow()
    }

    private fun buildFavouritesRow() {
        favRow.removeAllViews()
        val favApps = favOrder.mapNotNull { pkg -> allApps.find { it.pkg == pkg } }
        favHint.visibility = if (favApps.isEmpty()) View.VISIBLE else View.GONE

        var cellToFocus: View? = null
        for (app in favApps) {
            val grabbed = app.pkg == movingPkg
            val cell = makeCell(app, iconSizeDp = 76).apply {
                isFocusable = true
                background = if (grabbed) movingHighlight() else focusBackground()
                setOnClickListener {
                    if (movingPkg == app.pkg) stopMoving() else launch(app)
                }
                setOnLongClickListener {
                    if (movingPkg == app.pkg) stopMoving() else startMoving(app)
                    true
                }
                setOnFocusChangeListener { v, hasFocus ->
                    val s = if (hasFocus) 1.12f else 1f
                    v.animate().scaleX(s).scaleY(s).setDuration(120).start()
                }
                setOnKeyListener { _, keyCode, event ->
                    if (movingPkg != app.pkg) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (event.action == KeyEvent.ACTION_DOWN) moveFav(app.pkg, -1); true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (event.action == KeyEvent.ACTION_DOWN) moveFav(app.pkg, +1); true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_BACK -> {
                            if (event.action == KeyEvent.ACTION_DOWN) stopMoving(); true
                        }
                        else -> false
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(16) }
            }
            if (grabbed) cellToFocus = cell
            favRow.addView(cell)
        }
        // Keep focus on the grabbed app after a reorder redraw.
        cellToFocus?.let { c -> favRow.post { c.requestFocus() } }
    }

    // ---------------- Focus highlight ----------------

    private fun highlight(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(0x3300C2FF.toInt())
        setStroke(dp(3), 0xFF00C2FF.toInt())
    }

    private fun movingHighlight(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(0x55FF9800.toInt())
        setStroke(dp(3), 0xFFFF9800.toInt())
    }

    private fun transparentRounded(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(Color.TRANSPARENT)
    }

    private fun focusBackground(): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), highlight())
        addState(intArrayOf(android.R.attr.state_selected), highlight())
        addState(intArrayOf(android.R.attr.state_pressed), highlight())
        addState(intArrayOf(), transparentRounded())
    }

    // ---------------- Wallpaper ----------------

    private fun wallpaperFile() = File(filesDir, "wallpaper.jpg")

    private fun showCachedWallpaper() {
        val f = wallpaperFile()
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.let { wallpaperView.setImageBitmap(it) }
    }

    private fun refreshWallpaperAsync() {
        Thread {
            try {
                val feed = httpGet("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US")
                val images = JSONObject(feed).getJSONArray("images")
                if (images.length() == 0) return@Thread
                val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
                val chosen = images.getJSONObject(day % images.length())
                val imageUrl = "https://www.bing.com" + chosen.getString("urlbase") + "_1920x1080.jpg"
                val conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 12000
                }
                conn.inputStream.use { input ->
                    wallpaperFile().outputStream().use { out -> input.copyTo(out) }
                }
                val bmp: Bitmap? = BitmapFactory.decodeFile(wallpaperFile().absolutePath)
                if (bmp != null) runOnUiThread { wallpaperView.setImageBitmap(bmp) }
            } catch (e: Exception) { /* offline: keep what we have */ }
        }.start()
    }

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 12000
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    // ---------------- Apps ----------------

    private fun launch(app: AppInfo) {
        try { startActivity(app.launch) } catch (e: Exception) { /* uninstalled */ }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (movingPkg != null) stopMoving()   // Back cancels a move; otherwise stay put
    }

    private fun loadApps(): List<AppInfo> {
        val pm = packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val launch = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(category)
                    setClassName(pkg, ri.activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                out.add(AppInfo(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm), launch))
            }
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    // ---------------- View helpers ----------------

    /** A thin horizontal line that fades out toward both ends. */
    private fun divider(): View = View(this).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF)   // transparent -> faint white -> transparent
        )
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 16f
        setPadding(0, 0, 0, dp(8))
    }

    private fun makeCell(app: AppInfo, iconSizeDp: Int): LinearLayout {
        val size = dp(iconSizeDp)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = dp(12)
            setPadding(p, p, p, p)
            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                setImageDrawable(app.icon)
            })
            addView(TextView(context).apply {
                text = app.label
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                maxLines = 1
                val lp = LinearLayout.LayoutParams(size + dp(24),
                    LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.topMargin = dp(6)
                layoutParams = lp
            })
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()

    inner class AppAdapter : BaseAdapter() {
        override fun getCount(): Int = allApps.size
        override fun getItem(position: Int): Any = allApps[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = convertView as? LinearLayout ?: makeCell(allApps[position], iconSizeDp = 64)
            val app = allApps[position]
            (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }
    }
}

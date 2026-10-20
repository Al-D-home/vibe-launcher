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

/**
 * A deliberately tiny, fast TV launcher.
 *
 *  - Favourites row pinned at the top (hold OK on any app to add/remove it).
 *  - Grid of all your apps, with a clear focus highlight.
 *  - Auto-rotating nature wallpaper from Bing's daily image feed (no API key).
 *
 * No search bar, no recommendation rows, no ads, no background services.
 */
class MainActivity : Activity() {

    data class AppInfo(val pkg: String, val label: String, val icon: Drawable, val launch: Intent)

    private var allApps: List<AppInfo> = emptyList()
    private lateinit var adapter: AppAdapter

    private lateinit var wallpaperView: ImageView
    private lateinit var favRow: LinearLayout
    private lateinit var favHint: TextView

    private val prefs by lazy { getSharedPreferences("vibe", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        allApps = loadApps()

        // ---------- Wallpaper (behind everything) ----------
        wallpaperView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
        }
        val scrim = View(this).apply { setBackgroundColor(0x99000000.toInt()) }

        // ---------- Favourites row ----------
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

        // ---------- App grid ----------
        val grid = GridView(this).apply {
            numColumns = 5
            verticalSpacing = dp(28)
            horizontalSpacing = dp(28)
            isVerticalScrollBarEnabled = false
            selector = focusBackground()          // visible highlight on the focused app
            setDrawSelectorOnTop(true)            // draw the ring over the icon
            adapter = AppAdapter().also { this@MainActivity.adapter = it }
            setOnItemClickListener { _, _, pos, _ -> launch(allApps[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> toggleFavourite(allApps[pos]); true }
        }

        // ---------- Content column ----------
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

    private fun favPackages(): MutableSet<String> =
        prefs.getStringSet("favs", emptySet())!!.toMutableSet()

    private fun toggleFavourite(app: AppInfo) {
        val favs = favPackages()
        if (!favs.add(app.pkg)) favs.remove(app.pkg)
        prefs.edit().putStringSet("favs", favs).apply()
        Toast.makeText(
            this,
            if (favs.contains(app.pkg)) "Pinned ${app.label}" else "Unpinned ${app.label}",
            Toast.LENGTH_SHORT
        ).show()
        buildFavouritesRow()
    }

    private fun buildFavouritesRow() {
        favRow.removeAllViews()
        val favs = favPackages()
        val favApps = allApps.filter { favs.contains(it.pkg) }
        favHint.visibility = if (favApps.isEmpty()) View.VISIBLE else View.GONE
        for (app in favApps) {
            val cell = makeCell(app, big = true).apply {
                isFocusable = true
                background = focusBackground()
                setOnClickListener { launch(app) }
                setOnLongClickListener { toggleFavourite(app); true }
                setOnFocusChangeListener { v, hasFocus ->
                    val s = if (hasFocus) 1.12f else 1f
                    v.animate().scaleX(s).scaleY(s).setDuration(120).start()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(16) }
            }
            favRow.addView(cell)
        }
    }

    // ---------------- Focus highlight ----------------

    private fun highlight(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(0x3300C2FF.toInt())           // faint cyan tint
        setStroke(dp(3), 0xFF00C2FF.toInt())    // bright cyan ring
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
    override fun onBackPressed() { /* stay on the launcher */ }

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

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 16f
        setPadding(0, 0, 0, dp(8))
    }

    private fun makeCell(app: AppInfo, big: Boolean): LinearLayout {
        val size = if (big) dp(84) else dp(96)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = dp(14)
            setPadding(p, p, p, p)
            addView(ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                setImageDrawable(app.icon)
            })
            addView(TextView(context).apply {
                text = app.label
                setTextColor(Color.WHITE)
                textSize = 13f
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
            val cell = convertView as? LinearLayout ?: makeCell(allApps[position], big = false)
            val app = allApps[position]
            (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }
    }
}

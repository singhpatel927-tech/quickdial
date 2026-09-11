package com.gaurav.quickdial

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/* ------------------------------------------------------------------ */
/* helpers                                                             */
/* ------------------------------------------------------------------ */

fun Context.dp(v: Int): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

fun View.rounded(color: Int, radius: Int) {
    val g = GradientDrawable()
    g.setColor(color)
    g.cornerRadius = context.dp(radius).toFloat()
    background = g
}

/* ------------------------------------------------------------------ */
/* palette                                                             */
/* ------------------------------------------------------------------ */

object P {
    val ink = Color.parseColor("#0E1118")
    val raised = Color.parseColor("#181D29")
    val line = Color.parseColor("#252C3B")
    val text = Color.parseColor("#EAEDF4")
    val muted = Color.parseColor("#79839B")
    val signal = Color.parseColor("#F0A93B")
    val call = Color.parseColor("#2E9E63")
    val off = Color.parseColor("#4A5266")

    // ---- tile look -------------------------------------------------
    // Dark tiles (matches the rest of the app).
    val tileBg = raised
    val tileInk = text
    // For WHITE tiles like the mock-up, swap the two lines above for:
    // val tileBg = Color.parseColor("#FFFFFF")
    // val tileInk = Color.parseColor("#101010")
}

/* ------------------------------------------------------------------ */
/* data                                                                */
/* ------------------------------------------------------------------ */

data class Entry(val code: String, val name: String, val number: String)

object Store {
    private const val PREFS = "quickdial"

    /** 3 columns x 4 rows on each page. */
    const val COLS = 3
    const val SLOTS_PER_PAGE = 12
    const val PAGES = 2

    /** Slot codes: page 1 = 1..12, page 2 = 13..24. */
    fun slotsOfPage(page: Int): List<Int> {
        val first = page * SLOTS_PER_PAGE + 1
        return (first until first + SLOTS_PER_PAGE).toList()
    }

    /**
     * Labels pre-loaded on first run so the pad looks like the layout you
     * sketched. Numbers start empty — open Contacts, tap a row, type the
     * number, Save. Slot 22 and 24 are intentionally left blank.
     */
    private val DEFAULT_LABELS: List<Pair<String, String>> = listOf(
        // ---- page 1 ----
        "1" to "0-11",
        "2" to "11-26",
        "3" to "26-41",
        "4" to "980-996",
        "5" to "996-1011",
        "6" to "980-964",
        "7" to "949-964",
        "8" to "934-949",
        "9" to "919-934",
        "10" to "904-919",
        "11" to "ROU Officer",
        "12" to "890-904",
        // ---- page 2 ----
        "13" to "Sup 890-946",
        "14" to "Sup 946-980",
        "15" to "Sup 980-1011 0-11",
        "16" to "Sup 11-50",
        "17" to "BP 980-1011",
        "18" to "BP 980-964 0-11",
        "19" to "NPV 890-946",
        "20" to "NPV 946-980",
        "21" to "NPV 980-1011 0-11",
        "23" to "NPV 11-50"
    )

    fun load(ctx: Context): MutableList<Entry> {
        val raw = p(ctx).getString("items", "[]") ?: "[]"
        val out = mutableListOf<Entry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Entry(
                        o.getString("code"),
                        o.getString("name"),
                        o.optString("number", "")
                    )
                )
            }
        } catch (_: Exception) {
        }
        out.sortBy { it.code.toLongOrNull() ?: Long.MAX_VALUE }
        return out
    }

    fun save(ctx: Context, items: List<Entry>) {
        val arr = JSONArray()
        for (e in items) {
            arr.put(
                JSONObject()
                    .put("code", e.code)
                    .put("name", e.name)
                    .put("number", e.number)
            )
        }
        p(ctx).edit().putString("items", arr.toString()).apply()
    }

    /** true = tap selects, you then press Call. false = tap dials at once. */
    fun confirm(ctx: Context): Boolean = p(ctx).getBoolean("confirm", false)

    fun setConfirm(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean("confirm", on).apply()
    }

    /** Writes the default labels once, only if nothing is saved yet. */
    fun seedIfEmpty(ctx: Context) {
        val prefs = p(ctx)
        if (prefs.getBoolean("seeded", false)) return
        prefs.edit().putBoolean("seeded", true).apply()
        if (load(ctx).isNotEmpty()) return
        save(ctx, DEFAULT_LABELS.map { Entry(it.first, it.second, "") })
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/* ------------------------------------------------------------------ */
/* swipeable pager (no AndroidX needed)                                */
/* ------------------------------------------------------------------ */

class Pager(ctx: Context) : HorizontalScrollView(ctx) {

    private val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    var page = 0
        private set
    var onPageChanged: ((Int) -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(
            row,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        )
    }

    fun addPage(v: View) {
        row.addView(
            v,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /** Every page is exactly as wide as the pager itself. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > 0) {
            for (i in 0 until row.childCount) {
                val c = row.getChildAt(i)
                if (c.layoutParams.width != w) c.layoutParams.width = w
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val handled = super.onTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP ||
            ev.actionMasked == MotionEvent.ACTION_CANCEL
        ) snap()
        return handled
    }

    fun goTo(index: Int) {
        val w = width
        val target = index.coerceIn(0, (row.childCount - 1).coerceAtLeast(0))
        if (w > 0) smoothScrollTo(target * w, 0)
        if (target != page) {
            page = target
            onPageChanged?.invoke(page)
        }
    }

    private fun snap() {
        val w = width
        if (w == 0 || row.childCount == 0) return
        val target = ((scrollX + w / 2) / w).coerceIn(0, row.childCount - 1)
        smoothScrollTo(target * w, 0)
        if (target != page) {
            page = target
            onPageChanged?.invoke(page)
        }
    }
}

/* ------------------------------------------------------------------ */
/* main screen                                                         */
/* ------------------------------------------------------------------ */

class MainActivity : Activity() {

    private lateinit var nameView: TextView
    private lateinit var numberView: TextView
    private lateinit var callButton: Button
    private lateinit var pager: Pager
    private val dots = mutableListOf<View>()
    private val tiles = HashMap<Int, TextView>()

    private var items: List<Entry> = emptyList()
    private var selected: Entry? = null
    private var awaitingPermission: Entry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.seedIfEmpty(this)
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        items = Store.load(this)
        selected = selected?.let { s -> items.firstOrNull { it.code == s.code } }
        refresh()
    }

    /* ---------------- ui ---------------- */

    private fun build(): View {
        val rootView = LinearLayout(this)
        rootView.orientation = LinearLayout.VERTICAL
        rootView.setBackgroundColor(P.ink)
        rootView.fitsSystemWindows = true

        // header -------------------------------------------------------
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(18), dp(14), dp(12), 0)

        val title = TextView(this)
        title.text = "QUICK DIAL"
        title.setTextColor(P.muted)
        title.textSize = 13f
        title.letterSpacing = 0.14f
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val contacts = TextView(this)
        contacts.text = "Contacts"
        contacts.setTextColor(P.signal)
        contacts.textSize = 15f
        contacts.setPadding(dp(10), dp(10), dp(10), dp(10))
        contacts.setOnClickListener { openContacts(null) }
        header.addView(contacts)

        rootView.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // selection readout -------------------------------------------
        val readout = LinearLayout(this)
        readout.orientation = LinearLayout.VERTICAL
        readout.gravity = Gravity.CENTER
        readout.setPadding(dp(22), 0, dp(22), 0)

        nameView = TextView(this)
        nameView.textSize = 22f
        nameView.setTextColor(P.text)
        nameView.gravity = Gravity.CENTER
        readout.addView(nameView)

        numberView = TextView(this)
        numberView.textSize = 14f
        numberView.setTextColor(P.muted)
        numberView.gravity = Gravity.CENTER
        numberView.setPadding(0, dp(6), 0, 0)
        readout.addView(numberView)

        rootView.addView(readout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // page dots ----------------------------------------------------
        val dotRow = LinearLayout(this)
        dotRow.gravity = Gravity.CENTER
        dotRow.setPadding(0, 0, 0, dp(10))
        for (i in 0 until Store.PAGES) {
            val dot = View(this)
            dot.rounded(if (i == 0) P.signal else P.line, 3)
            val lp = LinearLayout.LayoutParams(dp(18), dp(5))
            lp.setMargins(dp(4), 0, dp(4), 0)
            dotRow.addView(dot, lp)
            dots.add(dot)
        }
        rootView.addView(
            dotRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // the pad ------------------------------------------------------
        pager = Pager(this)
        for (page in 0 until Store.PAGES) pager.addPage(padPage(page))
        pager.onPageChanged = { p -> updateDots(p) }
        rootView.addView(
            pager,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // bottom bar ---------------------------------------------------
        val bar = LinearLayout(this)
        bar.setPadding(dp(22), dp(14), dp(22), dp(20))

        val clear = TextView(this)
        clear.text = "⌫"
        clear.textSize = 20f
        clear.gravity = Gravity.CENTER
        clear.setTextColor(P.muted)
        clear.rounded(P.raised, 18)
        clear.isClickable = true
        clear.setOnClickListener {
            selected = null
            refresh()
        }
        bar.addView(clear, LinearLayout.LayoutParams(dp(62), dp(60)))

        callButton = Button(this)
        callButton.text = "Call"
        callButton.textSize = 17f
        callButton.isAllCaps = false
        callButton.setTextColor(Color.WHITE)
        callButton.rounded(P.call, 18)
        callButton.stateListAnimator = null
        callButton.setOnClickListener { selected?.let { placeCall(it) } }
        val callLp = LinearLayout.LayoutParams(0, dp(60), 1f)
        callLp.setMargins(dp(12), 0, 0, 0)
        bar.addView(callButton, callLp)

        rootView.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        return rootView
    }

    private fun padPage(page: Int): View {
        val grid = GridLayout(this)
        grid.columnCount = Store.COLS
        grid.setPadding(dp(14), 0, dp(14), 0)

        for (code in Store.slotsOfPage(page)) {
            val tile = TextView(this)
            tile.gravity = Gravity.CENTER
            tile.textSize = 14f
            tile.setTypeface(Typeface.DEFAULT_BOLD)
            tile.maxLines = 3
            tile.setLineSpacing(0f, 1.05f)
            tile.setPadding(dp(6), dp(4), dp(6), dp(4))
            tile.isClickable = true
            tile.setOnClickListener { onTileTap(code) }
            tile.setOnLongClickListener {
                openContacts(code)
                true
            }

            val lp = GridLayout.LayoutParams()
            lp.width = 0
            lp.height = dp(64)
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            lp.setMargins(dp(5), dp(5), dp(5), dp(5))
            grid.addView(tile, lp)

            tiles[code] = tile
        }

        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.addView(
            grid,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return wrap
    }

    /* ---------------- behaviour ---------------- */

    private fun entryFor(code: Int): Entry? = items.firstOrNull { it.code == code.toString() }

    private fun onTileTap(code: Int) {
        val entry = entryFor(code)
        if (entry == null) {
            Toast.makeText(this, "Empty slot — long press to set it up", Toast.LENGTH_SHORT).show()
            return
        }
        if (entry.number.isBlank()) {
            Toast.makeText(this, "No number saved for ${entry.name}", Toast.LENGTH_SHORT).show()
            openContacts(code)
            return
        }
        selected = entry
        refresh()
        if (!Store.confirm(this)) placeCall(entry)
    }

    private fun refresh() {
        for ((code, tile) in tiles) {
            val entry = entryFor(code)
            val isSelected = entry != null && entry.code == selected?.code
            tile.text = entry?.name ?: "+"
            when {
                entry == null -> {
                    tile.setTextColor(P.off)
                    tile.rounded(P.raised, 16)
                }
                entry.number.isBlank() -> {
                    tile.setTextColor(P.muted)
                    tile.rounded(P.raised, 16)
                }
                isSelected -> {
                    tile.setTextColor(Color.parseColor("#101010"))
                    tile.rounded(P.signal, 16)
                }
                else -> {
                    tile.setTextColor(P.tileInk)
                    tile.rounded(P.tileBg, 16)
                }
            }
        }

        val s = selected
        if (s == null) {
            nameView.text = "Tap a contact to dial"
            nameView.setTextColor(P.muted)
            numberView.text = ""
            enableCall(false)
        } else {
            nameView.text = s.name
            nameView.setTextColor(P.text)
            numberView.text = s.number
            enableCall(s.number.isNotBlank())
        }
    }

    private fun updateDots(active: Int) {
        for (i in dots.indices) dots[i].rounded(if (i == active) P.signal else P.line, 3)
    }

    private fun enableCall(on: Boolean) {
        callButton.isEnabled = on
        callButton.rounded(if (on) P.call else P.raised, 18)
        callButton.setTextColor(if (on) Color.WHITE else P.off)
    }

    private fun placeCall(entry: Entry) {
        if (entry.number.isBlank()) return
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            awaitingPermission = entry
            requestPermissions(arrayOf(android.Manifest.permission.CALL_PHONE), 101)
            return
        }
        try {
            val tel = "tel:" + Uri.encode(entry.number.replace(" ", ""))
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse(tel)))
        } catch (_: SecurityException) {
            Toast.makeText(this, "Calling permission was denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openContacts(code: Int?) {
        val i = Intent(this, ContactsActivity::class.java)
        if (code != null) i.putExtra("slot", code.toString())
        startActivity(i)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == 101) {
            val entry = awaitingPermission
            awaitingPermission = null
            if (grantResults.isNotEmpty() &&
                grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                entry != null
            ) {
                placeCall(entry)
            } else {
                Toast.makeText(this, "Allow phone calls to dial from here", Toast.LENGTH_LONG).show()
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* contacts screen                                                     */
/* ------------------------------------------------------------------ */

class ContactsActivity : Activity() {

    private lateinit var codeField: EditText
    private lateinit var nameField: EditText
    private lateinit var numberField: EditText
    private lateinit var listBox: LinearLayout
    private var items: MutableList<Entry> = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items = Store.load(this)
        setContentView(build())
        intent?.getStringExtra("slot")?.let { slot ->
            codeField.setText(slot)
            items.firstOrNull { it.code == slot }?.let {
                nameField.setText(it.name)
                numberField.setText(it.number)
            }
        }
        renderList()
    }

    private fun build(): View {
        val rootView = LinearLayout(this)
        rootView.orientation = LinearLayout.VERTICAL
        rootView.setBackgroundColor(P.ink)
        rootView.fitsSystemWindows = true

        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(12), dp(12), dp(18), dp(12))

        val back = TextView(this)
        back.text = "‹ Back"
        back.setTextColor(P.signal)
        back.textSize = 15f
        back.setPadding(dp(8), dp(8), dp(8), dp(8))
        back.setOnClickListener { finish() }
        header.addView(back)

        val heading = TextView(this)
        heading.text = "Contacts"
        heading.setTextColor(P.text)
        heading.textSize = 18f
        heading.setPadding(dp(8), 0, 0, 0)
        header.addView(heading)
        rootView.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val hint = TextView(this)
        hint.text = "Code 1–12 fills page 1 of the pad, 13–24 fills page 2 (left to right, top to bottom). Tap a row below to edit it."
        hint.setTextColor(P.muted)
        hint.textSize = 12f
        hint.setPadding(dp(18), 0, dp(18), dp(10))
        rootView.addView(
            hint,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val confirm = CheckBox(this)
        confirm.text = "Ask before dialing (tap a tile, then press Call)"
        confirm.setTextColor(P.text)
        confirm.textSize = 14f
        confirm.isChecked = Store.confirm(this)
        confirm.setPadding(dp(12), dp(4), dp(18), dp(10))
        confirm.setOnCheckedChangeListener { _, on -> Store.setConfirm(this, on) }
        rootView.addView(
            confirm,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(listBox)
        rootView.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val form = LinearLayout(this)
        form.orientation = LinearLayout.VERTICAL
        form.setPadding(dp(18), dp(14), dp(18), dp(20))

        val topRow = LinearLayout(this)
        codeField = field("Code", android.text.InputType.TYPE_CLASS_NUMBER)
        nameField = field(
            "Label shown on the tile",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        )
        topRow.addView(codeField, LinearLayout.LayoutParams(dp(84), dp(50)))
        val nameLp = LinearLayout.LayoutParams(0, dp(50), 1f)
        nameLp.setMargins(dp(10), 0, 0, 0)
        topRow.addView(nameField, nameLp)
        form.addView(
            topRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        numberField = field("Phone number", android.text.InputType.TYPE_CLASS_PHONE)
        val numLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50))
        numLp.setMargins(0, dp(10), 0, 0)
        form.addView(numberField, numLp)

        val pick = TextView(this)
        pick.text = "Pick from phone contacts"
        pick.setTextColor(P.signal)
        pick.textSize = 14f
        pick.setPadding(0, dp(12), 0, dp(12))
        pick.setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), 202
            )
        }
        form.addView(
            pick,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val saveBtn = Button(this)
        saveBtn.text = "Save contact"
        saveBtn.isAllCaps = false
        saveBtn.textSize = 16f
        saveBtn.setTextColor(Color.parseColor("#161207"))
        saveBtn.rounded(P.signal, 14)
        saveBtn.stateListAnimator = null
        saveBtn.setOnClickListener { save() }
        form.addView(
            saveBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        )

        rootView.addView(
            form,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return rootView
    }

    private fun field(hint: String, type: Int): EditText {
        val e = EditText(this)
        e.hint = hint
        e.inputType = type
        e.setHintTextColor(P.muted)
        e.setTextColor(P.text)
        e.textSize = 16f
        e.setPadding(dp(14), 0, dp(14), 0)
        e.rounded(P.raised, 12)
        return e
    }

    private fun renderList() {
        listBox.removeAllViews()
        if (items.isEmpty()) {
            val empty = TextView(this)
            empty.text = "No tiles yet.\nAdd a code from 1 to 24 below."
            empty.setTextColor(P.muted)
            empty.textSize = 15f
            empty.gravity = Gravity.CENTER
            empty.setPadding(dp(30), dp(44), dp(30), dp(44))
            listBox.addView(empty)
            return
        }
        for (entry in items) {
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(18), dp(10), dp(10), dp(10))
            row.isClickable = true
            row.setOnClickListener {
                codeField.setText(entry.code)
                nameField.setText(entry.name)
                numberField.setText(entry.number)
                numberField.requestFocus()
            }

            val codeChip = TextView(this)
            codeChip.text = entry.code
            codeChip.textSize = 18f
            codeChip.gravity = Gravity.CENTER
            codeChip.setTextColor(P.signal)
            codeChip.rounded(P.raised, 12)
            row.addView(codeChip, LinearLayout.LayoutParams(dp(48), dp(48)))

            val texts = LinearLayout(this)
            texts.orientation = LinearLayout.VERTICAL
            texts.setPadding(dp(14), 0, dp(8), 0)

            val name = TextView(this)
            name.text = entry.name
            name.textSize = 16f
            name.setTextColor(P.text)
            name.maxLines = 1
            texts.addView(name)

            val number = TextView(this)
            number.text = if (entry.number.isBlank()) "no number yet" else entry.number
            number.textSize = 13f
            number.setTextColor(if (entry.number.isBlank()) P.off else P.muted)
            texts.addView(number)

            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val remove = TextView(this)
            remove.text = "Remove"
            remove.textSize = 14f
            remove.setTextColor(P.muted)
            remove.setPadding(dp(10), dp(10), dp(10), dp(10))
            remove.setOnClickListener {
                items.remove(entry)
                Store.save(this, items)
                renderList()
            }
            row.addView(remove)

            listBox.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
    }

    private fun save() {
        val code = codeField.text.toString().trim()
        val name = nameField.text.toString().trim()
        val number = numberField.text.toString().trim()

        val slot = code.toIntOrNull()
        if (slot == null || slot < 1 || slot > Store.SLOTS_PER_PAGE * Store.PAGES) {
            Toast.makeText(
                this, "Code must be a number from 1 to ${Store.SLOTS_PER_PAGE * Store.PAGES}",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (name.isEmpty()) {
            Toast.makeText(this, "Add a label for the tile", Toast.LENGTH_SHORT).show()
            return
        }

        items.removeAll { it.code == code }
        items.add(Entry(code, name, number))
        items.sortBy { it.code.toLongOrNull() ?: Long.MAX_VALUE }
        Store.save(this, items)

        codeField.setText("")
        nameField.setText("")
        numberField.setText("")
        renderList()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 202 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (numIdx >= 0) numberField.setText(c.getString(numIdx))
                if (nameIdx >= 0 && nameField.text.toString().isBlank()) {
                    nameField.setText(c.getString(nameIdx))
                }
            }
        }
    }
}


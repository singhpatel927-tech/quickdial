package com.gaurav.quickdial

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

object P {
    val ink = Color.parseColor("#0E1118")
    val raised = Color.parseColor("#181D29")
    val line = Color.parseColor("#252C3B")
    val text = Color.parseColor("#EAEDF4")
    val muted = Color.parseColor("#79839B")
    val signal = Color.parseColor("#F0A93B")
    val call = Color.parseColor("#2E9E63")
    val off = Color.parseColor("#4A5266")
}

fun Context.dp(v: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
).toInt()

fun View.rounded(color: Int, radius: Int) {
    background = GradientDrawable().apply {
        setColor(color)
        cornerRadius = context.dp(radius).toFloat()
    }
}

data class Entry(val code: String, val name: String, val number: String)

object Store {
    private const val PREFS = "quickdial"

    fun load(ctx: Context): MutableList<Entry> {
        val raw = p(ctx).getString("items", "[]") ?: "[]"
        val out = mutableListOf<Entry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Entry(o.getString("code"), o.getString("name"), o.getString("number")))
            }
        } catch (e: Exception) {
        }
        out.sortBy { it.code.toLongOrNull() ?: Long.MAX_VALUE }
        return out
    }

    fun save(ctx: Context, items: List<Entry>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("code", it.code).put("name", it.name).put("number", it.number))
        }
        p(ctx).edit().putString("items", arr.toString()).apply()
    }

    fun auto(ctx: Context): Boolean = p(ctx).getBoolean("auto", false)
    fun setAuto(ctx: Context, on: Boolean) { p(ctx).edit().putBoolean("auto", on).apply() }

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class MainActivity : Activity() {

    private val IDLE_DELAY = 2000L

    private var code = ""
    private var items = mutableListOf<Entry>()
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    private lateinit var codeView: TextView
    private lateinit var nameView: TextView
    private lateinit var numberView: TextView
    private lateinit var callButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        items = Store.load(this)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        cancelPending()
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(P.ink)
            fitsSystemWindows = true
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(12), 0)
        }
        bar.addView(TextView(this).apply {
            text = "QUICK DIAL"
            setTextColor(P.muted)
            textSize = 13f
            letterSpacing = 0.14f
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(TextView(this).apply {
            text = "Contacts"
            setTextColor(P.signal)
            textSize = 15f
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ContactsActivity::class.java))
            }
        })
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val readout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(22), 0, dp(22), 0)
        }
        codeView = TextView(this).apply {
            textSize = 64f; setTextColor(P.signal); gravity = Gravity.CENTER
        }
        nameView = TextView(this).apply {
            textSize = 22f; setTextColor(P.text); gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        numberView = TextView(this).apply {
            textSize = 14f; setTextColor(P.muted); gravity = Gravity.CENTER
        }
        readout.addView(codeView); readout.addView(nameView); readout.addView(numberView)
        root.addView(readout, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val grid = GridLayout(this).apply {
            columnCount = 3
            setPadding(dp(18), 0, dp(18), 0)
        }
        listOf("1","2","3","4","5","6","7","8","9","","0","").forEach { d ->
            val cell = TextView(this).apply {
                text = d; textSize = 26f; gravity = Gravity.CENTER; setTextColor(P.text)
                if (d.isNotEmpty()) {
                    rounded(P.raised, 16)
                    isClickable = true
                    setOnClickListener { press(d) }
                }
            }
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(62)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        root.addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val actions = LinearLayout(this).apply {
            setPadding(dp(22), dp(14), dp(22), dp(20))
        }
        actions.addView(TextView(this).apply {
            text = "\u232B"; textSize = 20f; gravity = Gravity.CENTER
            setTextColor(P.muted); rounded(P.raised, 18); isClickable = true
            setOnClickListener { backspace() }
        }, LinearLayout.LayoutParams(dp(62), dp(60)))

        callButton = Button(this).apply {
            text = "Call"; textSize = 17f; isAllCaps = false
            setTextColor(Color.WHITE); rounded(P.call, 18)
            stateListAnimator = null
            setOnClickListener { placeCall() }
        }
        actions.addView(callButton, LinearLayout.LayoutParams(0, dp(60), 1f).apply {
            setMargins(dp(12), 0, 0, 0)
        })
        root.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return root
    }

    private fun press(d: String) {
        if (code.length >= 6) return
        code += d
        refresh()
    }

    private fun backspace() {
        if (code.isNotEmpty()) code = code.dropLast(1)
        refresh()
    }

    private fun match(): Entry? = items.firstOrNull { it.code == code }

    private fun refresh() {
        cancelPending()
        codeView.text = if (code.isEmpty()) "\u2014" else code
        codeView.setTextColor(if (code.isEmpty()) P.line else P.signal)

        val hit = match()
        if (hit != null) {
            nameView.text = hit.name
            nameView.setTextColor(P.text)
            numberView.text = hit.number
            enableCall(true)
            if (Store.auto(this)) {
                pending = Runnable { placeCall() }
                handler.postDelayed(pending!!, IDLE_DELAY)
            }
        } else {
            nameView.text = if (code.isEmpty()) "" else "No contact on this code"
            nameView.setTextColor(P.muted)
            numberView.text = ""
            enableCall(false)
        }
    }

    private fun enableCall(on: Boolean) {
        callButton.isEnabled = on
        callButton.rounded(if (on) P.call else P.raised, 18)
        callButton.setTextColor(if (on) Color.WHITE else P.off)
    }

    private fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    private fun placeCall() {
        cancelPending()
        val hit = match() ?: return
        if (checkSelfPermission(android.Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.CALL_PHONE), 101)
            return
        }
        val number = hit.number.replace(" ", "")
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))
            code = ""
        } catch (e: SecurityException) {
            Toast.makeText(this, "Calling permission was denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                placeCall()
            } else {
                Toast.makeText(this, "Allow phone calls to dial from here", Toast.LENGTH_LONG).show()
            }
        }
    }
}

class ContactsActivity : Activity() {

    private var items = mutableListOf<Entry>()
    private lateinit var listBox: LinearLayout
    private lateinit var codeField: EditText
    private lateinit var nameField: EditText
    private lateinit var numberField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        items = Store.load(this)
        setContentView(build())
        renderList()
    }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(P.ink)
            fitsSystemWindows = true
        }

        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(18), dp(12))
        }
        head.addView(TextView(this).apply {
            text = "\u2039 Back"; setTextColor(P.signal); textSize = 15f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { finish() }
        })
        head.addView(TextView(this).apply {
            text = "Contacts"; setTextColor(P.text); textSize = 18f
            setPadding(dp(8), 0, 0, 0)
        })
        root.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(CheckBox(this).apply {
            text = "Call automatically when a code matches"
            setTextColor(P.text); textSize = 14f
            isChecked = Store.auto(this@ContactsActivity)
            setPadding(dp(12), dp(12), dp(18), dp(12))
            setOnCheckedChangeListener { _, on -> Store.setAuto(this@ContactsActivity, on) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(listBox) },
            LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(20))
        }

        val pair = LinearLayout(this)
        codeField = field("Code", InputType.TYPE_CLASS_NUMBER)
        nameField = field("Name", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        pair.addView(codeField, LinearLayout.LayoutParams(dp(84), dp(50)))
        pair.addView(nameField, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            setMargins(dp(10), 0, 0, 0)
        })
        form.addView(pair, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        numberField = field("Phone number", InputType.TYPE_CLASS_PHONE)
        form.addView(numberField, LinearLayout.LayoutParams(MATCH_PARENT, dp(50)).apply {
            setMargins(0, dp(10), 0, 0)
        })

        form.addView(TextView(this).apply {
            text = "Pick from phone contacts"
            setTextColor(P.signal); textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                    202
                )
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        form.addView(Button(this).apply {
            text = "Save contact"; isAllCaps = false; textSize = 16f
            setTextColor(Color.parseColor("#161207"))
            rounded(P.signal, 14)
            stateListAnimator = null
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)))

        root.addView(form, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return root
    }

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint
        inputType = type
        setHintTextColor(P.muted)
        setTextColor(P.text)
        textSize = 16f
        setPadding(dp(14), 0, dp(14), 0)
        rounded(P.raised, 12)
    }

    private fun renderList() {
        listBox.removeAllViews()
        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "No codes yet.\nAdd one below, then type it on the keypad."
                setTextColor(P.muted); textSize = 15f; gravity = Gravity.CENTER
                setPadding(dp(30), dp(44), dp(30), dp(44))
            })
            return
        }
        items.forEach { entry ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(10), dp(10), dp(10))
            }
            row.addView(TextView(this).apply {
                text = entry.code; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(P.signal); rounded(P.raised, 12)
            }, LinearLayout.LayoutParams(dp(48), dp(48)))

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, dp(8), 0)
            }
            col.addView(TextView(this).apply {
                text = entry.name; textSize = 16f; setTextColor(P.text); maxLines = 1
            })
            col.addView(TextView(this).apply {
                text = entry.number; textSize = 13f; setTextColor(P.muted)
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

            row.addView(TextView(this).apply {
                text = "Remove"; textSize = 14f; setTextColor(P.muted)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener {
                    items.remove(entry)
                    Store.save(this@ContactsActivity, items)
                    renderList()
                }
            })
            listBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun save() {
        val code = codeField.text.toString().trim()
        val name = nameField.text.toString().trim()
        val number = numberField.text.toString().trim()

        if (!code.matches(Regex("\\d{1,6}"))) {
            Toast.makeText(this, "Code must be 1 to 6 digits", Toast.LENGTH_SHORT).show(); return
        }
        if (name.isEmpty() || number.isEmpty()) {
            Toast.makeText(this, "Add a name and a number", Toast.LENGTH_SHORT).show(); return
        }

        items.removeAll { it.code == code }
        items.add(Entry(code, name, number))
        items.sortBy { it.code.toLongOrNull() ?: Long.MAX_VALUE }
        Store.save(this, items)

        codeField.setText(""); nameField.setText(""); numberField.setText("")
        renderList()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 202 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val p = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (n >= 0) nameField.setText(c.getString(n))
                if (p >= 0) numberField.setText(c.getString(p))
            }
        }
    }
}

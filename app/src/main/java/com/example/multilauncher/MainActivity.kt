package com.example.multilauncher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.widget.*
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var listBox: LinearLayout
    private lateinit var delayInput: EditText
    private lateinit var rootCheck: CheckBox
    private lateinit var splitCheck: CheckBox
    private val checks = mutableListOf<Pair<String, CheckBox>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        root.addView(TextView(this).apply {
            text = "Multi Launcher"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Auto-detect installed packages starting with com.roblox, then open selected apps one by one."
            textSize = 13f
        })

        delayInput = EditText(this).apply {
            hint = "Delay seconds"
            setText("3")
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(delayInput)

        rootCheck = CheckBox(this).apply { text = "Root mode"; isChecked = true }
        root.addView(rootCheck)

        splitCheck = CheckBox(this).apply {
            text = "Split screen (freeform windows, needs Root)"
            isChecked = true
        }
        root.addView(splitCheck)

        root.addView(Button(this).apply {
            text = "REFRESH"
            setOnClickListener { refresh() }
        })
        root.addView(Button(this).apply {
            text = "OPEN SELECTED"
            setOnClickListener { openSelected() }
        })

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(listBox) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        refresh()
    }

    private fun findPackages(): List<String> {
        val result = linkedSetOf<String>()
        packageManager.getInstalledApplications(0)
            .map { it.packageName }
            .filter { it.startsWith("com.roblox") }
            .forEach { result.add(it) }
        if (rootCheck.isChecked) {
            runSu("pm list packages com.roblox").lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .forEach { result.add(it) }
        }
        return result.sorted()
    }

    private fun refresh() {
        listBox.removeAllViews()
        checks.clear()
        val pkgs = findPackages()
        if (pkgs.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "No com.roblox* packages found" })
            return
        }
        for (p in pkgs) {
            val label = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(p, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) { "" }
            val cb = CheckBox(this).apply { text = "$label\n$p" }
            checks.add(p to cb)
            listBox.addView(cb)
        }
    }

    private fun openSelected() {
        val selected = checks.filter { it.second.isChecked }.map { it.first }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Select at least one app", Toast.LENGTH_SHORT).show()
            return
        }
        val delayMs = (delayInput.text.toString().toLongOrNull() ?: 3L) * 1000
        val useRoot = rootCheck.isChecked
        val split = useRoot && splitCheck.isChecked

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels
        val h = dm.heightPixels
        val n = selected.size

        thread {
            for ((i, pkg) in selected.withIndex()) {
                if (useRoot) {
                    val comp = packageManager.getLaunchIntentForPackage(pkg)
                        ?.component?.flattenToShortString()
                    if (comp == null) continue
                    val mode = if (split) "--windowingMode 5 " else ""
                    runSu("am start $mode-n $comp")
                    Thread.sleep(delayMs)

                    if (split) {
                        // landscape: แบ่งซ้าย-ขวา / portrait: แบ่งบน-ล่าง
                        val l: Int; val t: Int; val r: Int; val b: Int
                        if (w >= h) {
                            l = w * i / n; t = 0; r = w * (i + 1) / n; b = h
                        } else {
                            l = 0; t = h * i / n; r = w; b = h * (i + 1) / n
                        }
                        var taskId: String? = null
                        repeat(6) {
                            if (taskId == null) {
                                taskId = findTaskId(pkg)
                                if (taskId == null) Thread.sleep(500)
                            }
                        }
                        taskId?.let { runSu("am task resize $it $l $t $r $b") }
                    }
                } else {
                    packageManager.getLaunchIntentForPackage(pkg)?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(it)
                    }
                    Thread.sleep(delayMs)
                }
            }
            runOnUiThread {
                Toast.makeText(this, "Done", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun findTaskId(pkg: String): String? {
        val out = runSu("dumpsys activity activities")
        val re = Regex("#(\\d+) [^\\n]*A=(?:\\d+:)?" + Regex.escape(pkg) + "\\b")
        return re.find(out)?.groupValues?.get(1)
    }

    private fun runSu(cmd: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (e: Exception) { "" }
}

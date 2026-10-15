package com.androbs.lens

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial
import com.androbs.lens.a11y.LensAccessibilityService
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val sourceTags = listOf("auto", "ja", "en", "it", "es")
    private val targetTags = listOf("it", "en", "es", "ja")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnStartTranslation).setOnClickListener {
            if (!isA11yEnabled()) {
                Toast.makeText(this, "Prima tocca 'Attiva traduzione allo scroll' e abilita Lente in Accessibilità", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            val a11y = LensAccessibilityService.instance
            if (a11y == null) {
                Toast.makeText(this, "Servizio di accessibilità in avvio... Riprova tra 2 secondi", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            a11y.setLockEnabled(true)
            Toast.makeText(this, "Lente attiva! Tocca '🔄 Traduci' in alto su qualsiasi app.", Toast.LENGTH_LONG).show()
            moveTaskToBack(true)
        }

        findViewById<Button>(R.id.btnStopTranslation).setOnClickListener {
            LensAccessibilityService.instance?.setLockEnabled(false)
            refreshStatus()
            Toast.makeText(this, "Traduzione fermata.", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSetAssistant).setOnClickListener {
            val rm = getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                !rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            ) {
                startActivity(rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }
        }

        findViewById<Button>(R.id.btnEnableA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        setupSpinner(
            findViewById(R.id.spinnerSource), sourceTags, Prefs.sourceLang(this)
        ) { tag -> Prefs.setSourceLang(this, tag) }
        setupSpinner(
            findViewById(R.id.spinnerTarget), targetTags, Prefs.targetLang(this)
        ) { tag -> Prefs.setTargetLang(this, tag) }

        findViewById<SwitchMaterial>(R.id.switchAutoTranslate).apply {
            isChecked = Prefs.autoTranslateOnOpen(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setAutoTranslateOnOpen(this@MainActivity, checked)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun setupSpinner(
        spinner: Spinner,
        tags: List<String>,
        current: String,
        onPick: (String) -> Unit
    ) {
        val uiLocale = resources.configuration.locales[0] ?: Locale.ENGLISH
        val labels = tags.map { tag ->
            if (tag == "auto") getString(R.string.lang_auto)
            else Locale.forLanguageTag(tag).getDisplayLanguage(uiLocale)
                .replaceFirstChar { it.uppercase(uiLocale) }
        }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spinner.setSelection(tags.indexOf(current).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                onPick(tags[pos])
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun refreshStatus() {
        val rm = getSystemService(RoleManager::class.java)
        val held = rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        findViewById<TextView>(R.id.assistantStatus).text = getString(
            if (held) R.string.status_assistant_ok else R.string.status_assistant_off
        )
        val a11yOn = isA11yEnabled()
        findViewById<TextView>(R.id.a11yStatus).text = getString(
            if (a11yOn) R.string.status_a11y_ok else R.string.status_a11y_off
        )

        val isLockRunning = LensAccessibilityService.instance?.isLockEnabled() == true
        findViewById<Button>(R.id.btnStopTranslation).visibility = if (isLockRunning) View.VISIBLE else View.GONE
        findViewById<Button>(R.id.btnStartTranslation).text = if (isLockRunning) "▶  TRADUZIONE GIÀ IN ESECUZIONE" else "▶  AVVIA TRADUZIONE SCHERMO"
    }

    private fun isA11yEnabled(): Boolean {
        val expected = "${packageName}/${LensAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (comp in splitter) {
            if (comp.equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}

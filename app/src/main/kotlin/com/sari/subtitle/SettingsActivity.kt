package com.sari.subtitle

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/** Settings hub. Each card opens a dedicated screen that persists through SettingsRepository. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.settingsToolbar).setNavigationOnClickListener { finish() }
        val destinations = listOf(
            R.id.card_keys to SettingsKeysActivity::class.java,
            R.id.card_stt to SettingsSttActivity::class.java,
            R.id.card_languages to SettingsLanguagesActivity::class.java,
            R.id.card_appearance to SettingsAppearanceActivity::class.java,
            R.id.card_quality to SettingsQualityActivity::class.java,
            R.id.card_context to SettingsContextActivity::class.java,
            R.id.card_omniroute to SettingsOmniRouteActivity::class.java,
            R.id.card_orchestration to SettingsOrchestrationActivity::class.java,
            R.id.card_history to HistoryActivity::class.java,
            R.id.card_about to SettingsAboutActivity::class.java
        )
        destinations.forEach { (viewId, activityClass) ->
            findViewById<View>(viewId).setOnClickListener { startActivity(Intent(this, activityClass)) }
        }
    }
}

package com.sari.subtitle

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/** Settings landing page. Each card opens one focused settings screen. */
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.settingsToolbar).setNavigationOnClickListener { finish() }
        bind(R.id.openKeysPageButton, SettingsKeysActivity::class.java)
        bind(R.id.openSttSettingsButton, SettingsSttActivity::class.java)
        bind(R.id.openLanguagesPageButton, SettingsLanguagesActivity::class.java)
        bind(R.id.openAppearancePageButton, SettingsAppearanceActivity::class.java)
        bind(R.id.openQualityPageButton, SettingsQualityActivity::class.java)
        bind(R.id.openContextPageButton, SettingsContextActivity::class.java)
        bind(R.id.openOmniRoutePageButton, SettingsOmniRouteActivity::class.java)
        bind(R.id.openOrchestrationPageButton, SettingsOrchestrationActivity::class.java)
        bind(R.id.openHistoryPageButton, HistoryActivity::class.java)
        bind(R.id.openAboutPageButton, SettingsAboutActivity::class.java)
    }

    private fun bind(viewId: Int, destination: Class<*>) {
        findViewById<MaterialButton>(viewId).setOnClickListener {
            startActivity(Intent(this, destination))
        }
    }
}

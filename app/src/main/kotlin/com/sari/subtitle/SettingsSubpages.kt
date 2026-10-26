package com.sari.subtitle

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

abstract class SettingsSubpageBase : AppCompatActivity() {
    abstract val page: String
    private lateinit var fields: List<android.widget.EditText>
    private lateinit var layouts: List<TextInputLayout>
    private lateinit var switches: List<MaterialSwitch>
    private lateinit var slider1: Slider
    private lateinit var slider2: Slider
    private lateinit var s: SettingsRepository
    private var current = AppSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layoutId = resources.getIdentifier("activity_settings_$page", "layout", packageName)
        check(layoutId != 0) { "Layout activity_settings_$page غير موجود" }
        setContentView(layoutId)
        s = SettingsRepository(this)
        findViewById<MaterialToolbar>(R.id.subpageToolbar).apply {
            title = pageTitle()
            setNavigationOnClickListener { finish() }
        }
        fields = listOf(R.id.field1,R.id.field2,R.id.field3,R.id.field4,R.id.field5).map { findViewById<android.widget.EditText>(it) }
        layouts = listOf(R.id.field1Layout,R.id.field2Layout,R.id.field3Layout,R.id.field4Layout,R.id.field5Layout).map { findViewById(it) }
        switches = listOf(R.id.switch1,R.id.switch2,R.id.switch3,R.id.switch4,R.id.switch5,R.id.switch6,R.id.switch7,R.id.switch8,R.id.switch9).map { findViewById(it) }
        slider1 = findViewById(R.id.slider1); slider2 = findViewById(R.id.slider2)
        findViewById<MaterialButton>(R.id.saveSubpageButton).setOnClickListener { savePage() }
        configure()
        lifecycleScope.launch { try { current=s.load(); populate() } catch(e:Exception) { toast(e.message ?: getString(R.string.settings_save_failed)) } }
    }
    private fun pageTitle() = when(page) {
        "keys" -> "مفاتيح API"; "languages" -> "اللغات"; "appearance" -> "المظهر"
        "quality" -> "جودة الترجمة"; "context" -> "الترجمة السياقية"; "omniroute" -> "OmniRoute"
        "orchestration" -> "AI Orchestration"; else -> getString(R.string.about)
    }
    private fun configure() {
        val labels = when(page) {
            "keys" -> listOf("مزوّد الترجمة (Groq / Google Cloud Translation / MyMemory / OmniRoute / OpenAI)","Groq API Key","Translation API Key","MyMemory Email")
            "languages" -> listOf("لغة التعرّف على الكلام (مثل auto أو ar)","لغة النص الأصلي (auto مسموح)","لغة الترجمة (مثل ar)","")
            "quality" -> listOf("الجودة: fast أو balanced أو human","ملاحظة","", "")
            "context" -> listOf("قاموس المصطلحات (مصطلح = ترجمة)","تعليمات السياق الإضافية","","")
            "omniroute" -> listOf("Base URL (HTTPS)","API Key","STT Models مفصولة بفاصلة","Translation Model","نماذج الترجمة البديلة مفصولة بفاصلة")
            "appearance" -> listOf("موضع الترجمة: top أو center أو bottom","","","")
            "about" -> listOf("Sari Subtitle","الإصدار: "+BuildConfig.VERSION_NAME,"ترجمة صوتية فورية مع نافذة عائمة","")
            else -> listOf("","","","")
        }
        fields.forEachIndexed { i, f -> val label = labels.getOrElse(i) { "" }; layouts[i].hint = label; f.hint = label }
        if (page == "keys") {
            (fields[0] as? MaterialAutoCompleteTextView)?.apply {
                setAdapter(android.widget.ArrayAdapter(this@SettingsSubpageBase, android.R.layout.simple_dropdown_item_1line,
                    listOf("Groq", "Google Cloud Translation", "MyMemory", "OmniRoute — Multi-Provider", "OpenAI")))
                inputType = android.text.InputType.TYPE_NULL
                setOnClickListener { showDropDown() }
            }
            layouts[1].endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            layouts[2].endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            fields[1].inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            fields[2].inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            fields[3].inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        } else if (page == "omniroute") {
            layouts[1].endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            fields[1].inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val visibleFields = when(page) { "keys"->4; "languages"->3; "quality"->2; "context"->2; "omniroute"->5; "about"->3; "appearance"->1; "orchestration"->2; else->0 }
        fields.forEachIndexed { i,f -> layouts[i].visibility=if(i<visibleFields) View.VISIBLE else View.GONE }
        switches.forEach { it.visibility=View.GONE }
        findViewById<TextView>(R.id.subpageDescription).text = when(page) {
            "keys" -> "تُحفظ المفاتيح باستخدام التخزين المشفر الحالي. لا تشارك مفاتيحك."
            "languages" -> "استخدم رموز لغات مدعومة من المزود المحدد. Auto Detect يعتمد على دعم المزود."
            "appearance" -> "تؤثر هذه القيم على مظهر الترجمة العائمة عند تطبيقها بواسطة OverlayService."
            "quality" -> "Human Quality لا يضمن جودة بشرية؛ إنه وضع معالجة أعلى كلفة/زمنًا."
            "context" -> "السياق يساعد على اتساق المصطلحات؛ وذاكرة الترجمة تقلل تكرار الطلبات."
            "omniroute" -> "أدخل عنوان بوابة متوافقًا مع OpenAI API. دعم النماذج يختلف حسب البوابة."
            "orchestration" -> "فعّل الميزات المدعومة. تتضمن الخيارات الإضافية التبديل الاحتياطي المتزامن وفترة القياس. لا تُفعّل إلا ما تدعمه إعدادات المشروع."
            else -> "معلومات الإصدار مأخوذة من إعدادات بناء التطبيق."
        }
        if(page=="appearance") {
            findViewById<TextView>(R.id.slider1Label).visibility=View.VISIBLE; slider1.visibility=View.VISIBLE
            findViewById<TextView>(R.id.slider2Label).visibility=View.VISIBLE; slider2.visibility=View.VISIBLE
            slider1.valueFrom=12f;slider1.valueTo=48f;slider1.stepSize=1f
            slider2.valueFrom=0f;slider2.valueTo=100f;slider2.stepSize=1f
            findViewById<TextView>(R.id.slider1Label).text="حجم الخط"
            findViewById<TextView>(R.id.slider2Label).text="الشفافية (%)"
            val preview = findViewById<TextView>(R.id.appearancePreview)
            fun updatePreview() {
                preview.textSize = slider1.value
                preview.alpha = slider2.value / 100f
                preview.text = "معاينة الترجمة العائمة · ${slider1.value.toInt()} sp · ${slider2.value.toInt()}%"
            }
            slider1.addOnChangeListener { _, _, _ -> updatePreview() }
            slider2.addOnChangeListener { _, _, _ -> updatePreview() }
            fields[0].addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePreview()
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
            updatePreview()
        } else { slider1.visibility=View.GONE;slider2.visibility=View.GONE;findViewById<TextView>(R.id.slider1Label).visibility=View.GONE;findViewById<TextView>(R.id.slider2Label).visibility=View.GONE }
        if(page=="context") {
            switches[0].visibility=View.VISIBLE; switches[0].text="استخدام الترجمة السياقية"
            switches[1].visibility=View.VISIBLE; switches[1].text="تفعيل ذاكرة الترجمة"
            fields[0].inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            fields[0].minLines = 3; fields[0].gravity = android.view.Gravity.TOP or android.view.Gravity.START
            fields[1].inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            fields[1].minLines = 4; fields[1].gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        if(page=="quality") fields[1].isEnabled = false
        if(page=="about") fields.forEach { it.isEnabled = false }
        if(page=="orchestration") {
            val names=listOf("Smart Routing","Quality-aware Retry","Compare Candidates","Streaming Orchestration","Quota Monitoring","Provider Benchmark","Circuit Breaker","Adaptive Routing","Hedged Failover")
            switches.forEachIndexed { i,v -> v.visibility=View.VISIBLE;v.text=names[i] }
            layouts[0].hint = "فترة القياس الدوري (بالساعات)"
            layouts[1].hint = "تأخير الإرسال الاحتياطي (مللي ثانية)"
            layouts[2].visibility = View.GONE
            fields[0].inputType = android.text.InputType.TYPE_CLASS_NUMBER
            fields[1].inputType = android.text.InputType.TYPE_CLASS_NUMBER
            fields[2].inputType = android.text.InputType.TYPE_CLASS_NUMBER
            switches[8].visibility = View.VISIBLE
            switches[8].text = "Hedged Failover"
        }
        if(page=="about") findViewById<MaterialButton>(R.id.saveSubpageButton).visibility=View.GONE
        if(page=="keys") {
            findViewById<MaterialButton>(R.id.saveSubpageButton).text=getString(R.string.save_settings)
            val b=MaterialButton(this).apply { text="الحصول على مفتاح Groq"; setOnClickListener { open("https://console.groq.com/keys") } }
            (findViewById<View>(R.id.saveSubpageButton).parent as? android.widget.LinearLayout)?.addView(b, (findViewById<View>(R.id.saveSubpageButton).parent as android.widget.LinearLayout).indexOfChild(findViewById(R.id.saveSubpageButton)))
        }
        if(page=="omniroute") {
            val b=MaterialButton(this).apply { text="اختبار اتصال OmniRoute"; setOnClickListener { testOmni() } }
            (findViewById<View>(R.id.saveSubpageButton).parent as? android.widget.LinearLayout)?.addView(b, (findViewById<View>(R.id.saveSubpageButton).parent as android.widget.LinearLayout).indexOfChild(findViewById(R.id.saveSubpageButton)))
        }
        if(page=="orchestration") {
            val b=MaterialButton(this).apply {
                text = "فتح لوحة مزوّدي الذكاء الاصطناعي"
                setOnClickListener { startActivity(Intent(this@SettingsSubpageBase, ProviderDashboardActivity::class.java)) }
            }
            val parent = findViewById<View>(R.id.saveSubpageButton).parent as? android.widget.LinearLayout
            parent?.addView(b, parent.indexOfChild(findViewById(R.id.saveSubpageButton)))
        }
    }
    private fun populate() {
        when(page) {
            "keys" -> { fields[0].setText(listOf("Groq","Google Cloud Translation","MyMemory","OmniRoute — Multi-Provider","OpenAI").getOrElse(current.provider){"Groq"});fields[1].setText(current.speechKey);fields[2].setText(current.translationKey);fields[3].setText(current.myMemoryEmail) }
            "languages" -> { fields[0].setText(current.speechLanguage);fields[1].setText(current.sourceLanguage);fields[2].setText(current.targetLanguage) }
            "quality" -> { fields[0].setText(listOf("Fast","Balanced","Human Quality")[current.translationQuality.coerceIn(0,2)]);fields[1].setText("Human Quality does not guarantee human review.") }
            "context" -> { fields[0].setText(current.glossary);fields[1].setText(current.contextPrompt);switches[0].isChecked=current.contextAwareTranslation;switches[1].isChecked=current.translationMemoryEnabled }
            "appearance" -> { fields[0].setText(current.subtitlePosition); slider1.value=current.fontSize.coerceIn(12,48).toFloat();slider2.value=current.opacity.coerceIn(0,100).toFloat() }
            "omniroute" -> { fields[0].setText(current.omniRouteBaseUrl);fields[1].setText(current.omniRouteKey);fields[2].setText(current.omniRouteSttModels);fields[3].setText(current.omniRouteTranslationModel);fields[4].setText(current.omniRouteTranslationModels) }
            "orchestration" -> { fields[0].setText((current.benchmarkIntervalMs / 3_600_000L).toString()); fields[1].setText(current.hedgeDelayMs.toString()); fields[2].setText(""); switches[0].isChecked=current.smartRoutingEnabled;switches[1].isChecked=current.qualityRetryEnabled;switches[2].isChecked=current.compareCandidatesEnabled;switches[3].isChecked=current.streamingOrchestrationEnabled;switches[4].isChecked=current.quotaMonitoringEnabled;switches[5].isChecked=current.providerBenchmarkEnabled;switches[6].isChecked=current.circuitBreakerEnabled;switches[7].isChecked=current.adaptiveRoutingEnabled;switches[8].isChecked=current.hedgedFailoverEnabled }
            "about" -> { fields[0].setText(getString(R.string.app_name)); fields[1].setText(BuildConfig.VERSION_NAME); fields[2].setText(getString(R.string.about_description)) }
        }
    }
    private fun savePage() {
        val v=fields.map { it.text?.toString()?.trim().orEmpty() }
        current=when(page) {
            "keys" -> current.copy(provider=when(v[0]){"Google Cloud Translation"->1;"MyMemory"->2;"OmniRoute — Multi-Provider"->3;"OpenAI"->4;else->0},speechKey=v[1],translationKey=v[2],myMemoryEmail=v[3])
            "languages" -> current.copy(speechLanguage=v[0].ifBlank{"auto"},sourceLanguage=v[1].ifBlank{"auto"},targetLanguage=v[2].ifBlank{"ar"})
            "quality" -> current.copy(translationQuality=when(v[0].lowercase()){"fast","سريع"->0;"balanced","متوازن"->1;else->2})
            "context" -> current.copy(glossary=v[0],contextPrompt=v[1],contextAwareTranslation=switches[0].isChecked,translationMemoryEnabled=switches[1].isChecked)
            "appearance" -> current.copy(fontSize=slider1.value.toInt(),opacity=slider2.value.toInt(),subtitlePosition=when(v[0].lowercase()){"top"->"top";"center","middle"->"center";else->"bottom"})
            "omniroute" -> current.copy(omniRouteBaseUrl=v[0],omniRouteKey=v[1],omniRouteSttModels=v[2],omniRouteTranslationModel=v[3],omniRouteTranslationModels=v[4])
            "orchestration" -> current.copy(smartRoutingEnabled=switches[0].isChecked,qualityRetryEnabled=switches[1].isChecked,compareCandidatesEnabled=switches[2].isChecked,streamingOrchestrationEnabled=switches[3].isChecked,quotaMonitoringEnabled=switches[4].isChecked,providerBenchmarkEnabled=switches[5].isChecked,circuitBreakerEnabled=switches[6].isChecked,adaptiveRoutingEnabled=switches[7].isChecked,hedgedFailoverEnabled=switches[8].isChecked,benchmarkIntervalMs=(v[0].toLongOrNull()?.coerceIn(1,720) ?: (current.benchmarkIntervalMs/3_600_000L))*3_600_000L,hedgeDelayMs=v[1].toLongOrNull()?.coerceIn(0,2000) ?: current.hedgeDelayMs)
            else -> current
        }
        if(page=="omniroute" && v[0].isNotBlank()) {
            val parsed = runCatching { Uri.parse(v[0]) }.getOrNull()
            if (!android.util.Patterns.WEB_URL.matcher(v[0]).matches() || parsed?.scheme !in listOf("https", "http") || parsed.host.isNullOrBlank()) { toast("عنوان URL غير صالح؛ أدخل عنوان HTTP(S) كاملًا"); return }
        }
        if(page=="languages") {
            val languagePattern = Regex("(?i)^(auto|[a-z]{2,3}([_-][a-z]{2,4})?)$")
            if (v.take(3).any { it.isBlank() || !languagePattern.matches(it) }) { toast("أدخل رموز لغات صحيحة مثل en وar، واستخدم auto للكشف التلقائي حيث يدعمه المزود."); return }
            if (v[2].equals("auto", true)) { toast("لغة الترجمة الهدف لا يمكن أن تكون auto؛ اختر لغة محددة مثل ar."); return }
        }
        if(page=="keys" && v[0] !in listOf("Groq","Google Cloud Translation","MyMemory","OmniRoute — Multi-Provider","OpenAI")) { toast("اختر مزوّد ترجمة من القيم المعروفة"); return }
        lifecycleScope.launch { try {
            s.saveSnapshot(current)
            if (page == "appearance") startService(Intent(this@SettingsSubpageBase, OverlayService::class.java).setAction(OverlayService.ACTION_APPLY_SETTINGS))
            toast(getString(R.string.settings_saved)); finish()
        } catch(e:Exception) { toast(e.message ?: getString(R.string.settings_save_failed)) } }
    }
    private fun testOmni() { val u=fields[0].text?.toString()?.trim().orEmpty(); val k=fields[1].text?.toString()?.trim().orEmpty(); if(!u.startsWith("https://")||k.isBlank()){toast("أدخل Base URL آمنًا ومفتاح API");return}; lifecycleScope.launch { try { val r=OmniRouteClient(u,k).healthCheck(); toast(if(r.reachable) "نجح الاتصال؛ ${r.modelCount} نموذجًا، ${r.latencyMs}ms" else "فشل الاتصال: ${r.error}") } catch(e:Exception) { toast("فشل الاتصال: ${e.message}") } } }
    private fun open(url:String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch(e:Exception) { toast("تعذر فتح الرابط") } }
    private fun toast(m:String) = Toast.makeText(this,m,Toast.LENGTH_LONG).show()
}
class SettingsKeysActivity: SettingsSubpageBase() { override val page="keys" }
class SettingsLanguagesActivity: SettingsSubpageBase() { override val page="languages" }
class SettingsAppearanceActivity: SettingsSubpageBase() { override val page="appearance" }
class SettingsQualityActivity: SettingsSubpageBase() { override val page="quality" }
class SettingsContextActivity: SettingsSubpageBase() { override val page="context" }
class SettingsOmniRouteActivity: SettingsSubpageBase() { override val page="omniroute" }
class SettingsOrchestrationActivity: SettingsSubpageBase() { override val page="orchestration" }
class SettingsAboutActivity: SettingsSubpageBase() { override val page="about" }

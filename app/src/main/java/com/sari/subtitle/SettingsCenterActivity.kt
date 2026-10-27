package com.sari.subtitle

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sari.subtitle.v2.BuildConfig
import kotlin.math.roundToInt

/**
 * Stage 36 — Premium Settings UI.
 *
 * This screen is a presentation layer over the existing Stage 35 settings state.
 * It intentionally reuses SubtitleSettings, SettingsDialog and
 * TranslationProvidersDialog instead of introducing a second settings store.
 * Changes made by the controls below are persisted immediately through the
 * existing SubtitleSettings properties.
 */
class SettingsCenterActivity : AppCompatActivity() {

    private lateinit var settings: SubtitleSettings
    private lateinit var rootContent: LinearLayout
    private lateinit var appearancePreview: TextView
    private lateinit var sizeValue: TextView
    private lateinit var offsetValue: TextView
    private lateinit var boldSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SubtitleSettings(applicationContext)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (::rootContent.isInitialized) refreshUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 11, 22))
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }

        root.addView(buildTopBar())

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        rootContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        scroll.addView(rootContent)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        refreshUi()
    }

    private fun buildTopBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(14), dp(12), dp(12))
        setBackgroundColor(Color.rgb(17, 24, 42))

        addView(Button(this@SettingsCenterActivity).apply {
            text = "‹"
            textSize = 28f
            setTextColor(Color.WHITE)
            background = transparentBackground()
            minWidth = dp(44)
            minHeight = dp(44)
            contentDescription = "رجوع"
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        })

        addView(LinearLayout(this@SettingsCenterActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@SettingsCenterActivity).apply {
                text = "الإعدادات"
                textSize = 23f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@SettingsCenterActivity).apply {
                text = "Sari Subtitle  •  مركز التحكم"
                textSize = 12f
                setTextColor(Color.rgb(167, 176, 200))
                setPadding(0, dp(2), 0, 0)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))

        addView(TextView(this@SettingsCenterActivity).apply {
            text = "STAGE 36"
            textSize = 10f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(Color.rgb(139, 92, 246), 22f)
        })
    }

    private fun refreshUi() {
        rootContent.removeAllViews()

        addHero()
        addSectionHeader("الوضع الحالي", "نظرة سريعة على المحرك والمزوّد والمظهر")
        addStatusRow(
            "الترجمة",
            translationProviderLabel(),
            translationStatusText()
        )
        addStatusRow(
            "التفريغ الصوتي STT",
            settings.sttProviderLabel,
            sttStatusText()
        )
        addStatusRow(
            "مظهر الترجمة",
            "${settings.textSize.toInt()}sp • ${if (settings.bold) "عريض" else "عادي"} • ${SubtitleSettings.POSITION_LABELS[settings.position.coerceIn(0, 2)]}",
            "يُطبّق مباشرة على إعدادات SubtitleSettings الحالية."
        )

        addSectionHeader("الترجمة", "اضبط المسار الأساسي دون فقدان الإعدادات الحالية")
        addTranslationCard()

        addSectionHeader("الصوت والتفريغ", "STT سحابي أو Vosk محلي حسب إعدادات المشروع الحالية")
        addSttCard()

        addSectionHeader("مظهر الترجمة", "تغييرات المعاينة تحفظ فورًا")
        addAppearanceCard()

        addSectionHeader("الخدمات والأمان", "الوصول إلى مفاتيح API والمزوّدين والتشخيص")
        addActionCard("مركز مزوّدي الخدمات", "اختبار اتصال حقيقي • READY / 401 / 429 / 5xx • latency", "فتح مركز الصحة") {
            startActivity(android.content.Intent(this, ProviderCenterActivity::class.java))
        }
        addActionCard("مفاتيح API", "المفاتيح لا تظهر في هذه الصفحة", "فتح التخزين الآمن الحالي") {
            openLegacySettings()
        }
        addActionCard("السجل والتشخيص", "العمليات والأخطاء المنقّحة", "فتح سجل العمليات") {
            OperationLogStore.showHistory(this)
        }

        addSectionHeader("النظام", "إعدادات لا نغيّر سلوكها في هذه المرحلة")
        addActionCard("التخزين والتنزيلات", "نماذج Vosk والملفات المحلية", "إدارة النماذج من الإعدادات الحالية") {
            openLegacySettings()
        }
        addActionCard("الخصوصية والأذونات", "لا توجد صلاحيات جديدة في Stage 36", "عرض سياسة الاستخدام") {
            showPrivacyInfo()
        }
        addActionCard("حول التطبيق", "الإصدار والبناء والحزمة الحالية", "عرض معلومات الإصدار") {
            showAbout()
        }
    }

    private fun addHero() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.rgb(24, 34, 58), 20f, Color.rgb(48, 59, 90))
        }

        card.addView(TextView(this).apply {
            text = "تحكم كامل في الترجمة"
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = "اضبط التطبيق مرة واحدة، وستبقى الاختيارات متزامنة مع المشغّل ومحرّك الترجمة."
            textSize = 13f
            setTextColor(Color.rgb(190, 198, 220))
            setPadding(0, dp(5), 0, dp(12))
        })

        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        quick.addView(heroMetric("الهدف", "العربية"))
        quick.addView(heroMetric("الحجم", "${settings.textSize.toInt()}sp"))
        quick.addView(heroMetric("المحرّك", if (settings.processingEngine == SubtitleSettings.ENGINE_INTERNAL) "Sari" else "SubWhisper"))
        card.addView(quick)

        addCardToContent(card, 0)
    }

    private fun heroMetric(title: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = rounded(Color.rgb(17, 24, 42), 14f)
        addView(TextView(this@SettingsCenterActivity).apply {
            text = title
            textSize = 10f
            setTextColor(Color.rgb(130, 140, 165))
        })
        addView(TextView(this@SettingsCenterActivity).apply {
            text = value
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, 0)
        })
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
            setMargins(dp(3), 0, dp(3), 0)
        }
    }

    private fun addTranslationCard() {
        val card = cardBase()
        card.addView(label("المزوّد الأساسي", 12f, Color.rgb(167, 176, 200)))

        val provider = darkSpinner(
            arrayOf("تلقائي", "Gemini", "OmniRoute أولاً"),
            when (settings.translationProvider) {
                SubtitleSettings.TRANSLATION_GEMINI -> 1
                SubtitleSettings.TRANSLATION_OMNIROUTE -> 2
                else -> 0
            }
        )
        provider.onItemSelectedListener = simpleItemListener { index ->
            settings.translationProvider = when (index) {
                1 -> SubtitleSettings.TRANSLATION_GEMINI
                2 -> SubtitleSettings.TRANSLATION_OMNIROUTE
                else -> SubtitleSettings.TRANSLATION_AUTO
            }
            refreshUi()
        }
        card.addView(provider)

        card.addView(label("لغة المصدر", 12f, Color.rgb(167, 176, 200)))
        card.addView(infoValue("اكتشاف تلقائي حسب مسار STT الحالي"))
        card.addView(label("لغة الهدف", 12f, Color.rgb(167, 176, 200)))
        card.addView(infoValue("العربية — المسار الحالي للتطبيق"))

        addButtonRow(card, "اختبار إعدادات الترجمة", "فتح إعدادات المزوّد الحالية") {
            TranslationProvidersDialog.show(this, settings)
        }
        rootContent.addView(card)
    }

    private fun addSttCard() {
        val card = cardBase()
        card.addView(label("مزوّد Speech-to-Text", 12f, Color.rgb(167, 176, 200)))
        val stt = darkSpinner(
            arrayOf("تلقائي — المجاني أولاً", "Groq Whisper", "Deepgram Nova-3", "OpenAI Whisper", "Vosk محلي"),
            when (settings.sttProvider) {
                SubtitleSettings.STT_GROQ -> 1
                SubtitleSettings.STT_DEEPGRAM -> 2
                SubtitleSettings.STT_OPENAI -> 3
                SubtitleSettings.STT_LOCAL -> 4
                else -> 0
            }
        )
        stt.onItemSelectedListener = simpleItemListener { index ->
            settings.sttProvider = when (index) {
                1 -> SubtitleSettings.STT_GROQ
                2 -> SubtitleSettings.STT_DEEPGRAM
                3 -> SubtitleSettings.STT_OPENAI
                4 -> SubtitleSettings.STT_LOCAL
                else -> SubtitleSettings.STT_AUTO
            }
            refreshUi()
        }
        card.addView(stt)
        card.addView(infoValue(sttStatusText()))
        addButtonRow(card, "إدارة نماذج Vosk / مفاتيح STT", "إعادة استخدام شاشة الإعدادات الحالية") {
            openLegacySettings()
        }
        rootContent.addView(card)
    }

    private fun addAppearanceCard() {
        val card = cardBase()
        appearancePreview = TextView(this).apply {
            text = "هذه معاينة الترجمة العربية"
            gravity = Gravity.CENTER
            minHeight = dp(90)
            textSize = settings.textSize
            setTextColor(settings.textColor)
            setTypeface(typeface, if (settings.bold) Typeface.BOLD else Typeface.NORMAL)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = previewBackground()
        }
        card.addView(appearancePreview)

        card.addView(label("حجم النص", 12f, Color.rgb(167, 176, 200)))
        sizeValue = label("${settings.textSize.toInt()}sp", 13f, Color.WHITE)
        card.addView(sizeValue)
        card.addView(SeekBar(this).apply {
            max = SubtitleSettings.TEXT_SIZES.lastIndex
            progress = settings.textSizeIndex.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    settings.textSizeIndex = progress
                    updatePreviewOnly()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })

        card.addView(label("موضع الترجمة", 12f, Color.rgb(167, 176, 200)))
        card.addView(infoValue(if (settings.hasCustomSubtitlePosition) {
            "موضع مخصص محفوظ — اسحب الترجمة بإصبعك فوق الفيديو لتغييره."
        } else {
            "الموضع الافتراضي: أسفل الشاشة — ويمكنك سحب الترجمة مباشرة أثناء العرض."
        }))
        val position = darkSpinner(SubtitleSettings.POSITION_LABELS, settings.position.coerceIn(0, 2))
        position.onItemSelectedListener = simpleItemListener { index ->
            settings.position = index
            settings.hasCustomSubtitlePosition = false
            updatePreviewOnly()
        }
        card.addView(position)
        addButtonRow(card, "إعادة تعيين موضع الترجمة", "إرجاعها إلى أسفل الشاشة في المنتصف") {
            settings.resetSubtitlePosition()
            Toast.makeText(this, "تمت إعادة موضع الترجمة إلى أسفل الشاشة", Toast.LENGTH_SHORT).show()
            refreshUi()
        }

        val landscapeSwitch = Switch(this).apply {
            text = "تفضيل العرض الأفقي Landscape"
            textSize = 13f
            setTextColor(Color.WHITE)
            isChecked = settings.preferredLandscape
            setOnCheckedChangeListener { _, checked ->
                settings.preferredLandscape = checked
                Toast.makeText(
                    this@SettingsCenterActivity,
                    if (checked) "تم حفظ تفضيل العرض الأفقي" else "تم حفظ تفضيل العرض العمودي",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        card.addView(landscapeSwitch)

        card.addView(label("لون النص", 12f, Color.rgb(167, 176, 200)))
        val textColor = darkSpinner(SubtitleSettings.TEXT_COLOR_LABELS, settings.textColorIndex.coerceIn(0, SubtitleSettings.TEXT_COLOR_LABELS.lastIndex))
        textColor.onItemSelectedListener = simpleItemListener { index ->
            settings.textColorIndex = index
            updatePreviewOnly()
        }
        card.addView(textColor)

        card.addView(label("خلفية الترجمة", 12f, Color.rgb(167, 176, 200)))
        val bgColor = darkSpinner(SubtitleSettings.BG_COLOR_LABELS, settings.bgColorIndex.coerceIn(0, SubtitleSettings.BG_COLOR_LABELS.lastIndex))
        bgColor.onItemSelectedListener = simpleItemListener { index ->
            settings.bgColorIndex = index
            updatePreviewOnly()
        }
        card.addView(bgColor)

        boldSwitch = Switch(this).apply {
            text = "خط عريض Bold"
            textSize = 13f
            setTextColor(Color.WHITE)
            isChecked = settings.bold
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                settings.bold = checked
                updatePreviewOnly()
            }
        }
        card.addView(boldSwitch)

        offsetValue = label("التوقيت: ${settings.offsetMs} ms", 12f, Color.rgb(167, 176, 200))
        card.addView(offsetValue)
        card.addView(SeekBar(this).apply {
            max = 4000
            progress = (settings.offsetMs.coerceIn(-2000L, 2000L) + 2000L).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    settings.offsetMs = progress - 2000L
                    offsetValue.text = "التوقيت: ${settings.offsetMs} ms"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })

        addButtonRow(card, "استعادة مظهر الترجمة", "يعيد هذا القسم فقط إلى القيم الافتراضية") {
            settings.textSizeIndex = 2
            settings.resetSubtitlePosition()
            settings.textColorIndex = 0
            settings.bgColorIndex = 0
            settings.bold = true
            settings.offsetMs = 0L
            Toast.makeText(this, "تمت استعادة مظهر الترجمة فقط", Toast.LENGTH_SHORT).show()
            refreshUi()
        }
        rootContent.addView(card)
    }

    private fun updatePreviewOnly() {
        if (!::appearancePreview.isInitialized) return
        appearancePreview.textSize = settings.textSize
        appearancePreview.setTextColor(settings.textColor)
        appearancePreview.setTypeface(appearancePreview.typeface, if (settings.bold) Typeface.BOLD else Typeface.NORMAL)
        appearancePreview.background = previewBackground()
        if (::sizeValue.isInitialized) sizeValue.text = "${settings.textSize.toInt()}sp"
        if (::offsetValue.isInitialized) offsetValue.text = "التوقيت: ${settings.offsetMs} ms"
    }

    private fun previewBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(settings.bgColor)
        setStroke(dp(1), Color.argb(80, 255, 255, 255))
    }

    private fun addStatusRow(title: String, value: String, detail: String) {
        val card = cardBase()
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f, Color.WHITE))
        texts.addView(label(value, 13f, Color.rgb(190, 198, 220)))
        texts.addView(label(detail, 11f, Color.rgb(130, 140, 165)))
        top.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        val state = label(if (title == "الترجمة") "STATE" else "INFO", 9f, Color.WHITE)
        state.gravity = Gravity.CENTER
        state.setPadding(dp(8), dp(5), dp(8), dp(5))
        state.background = rounded(Color.rgb(48, 59, 90), 18f)
        top.addView(state)
        card.addView(top)
        rootContent.addView(card)
    }

    private fun addSectionHeader(title: String, subtitle: String) {
        rootContent.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), dp(14), dp(2), dp(2))
        })
        rootContent.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.rgb(130, 140, 165))
            setPadding(dp(2), 0, dp(2), dp(8))
        })
    }

    private fun addActionCard(title: String, subtitle: String, trailing: String, action: () -> Unit) {
        val card = cardBase().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(title, 15f, Color.WHITE))
        texts.addView(label(subtitle, 12f, Color.rgb(167, 176, 200)))
        texts.addView(label(trailing, 11f, Color.rgb(130, 140, 165)))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label("›", 28f, Color.rgb(167, 139, 250)))
        card.addView(row)
        rootContent.addView(card)
    }

    private fun addButtonRow(card: LinearLayout, title: String, subtitle: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = "$title  ›"
            setAllCaps(false)
            textSize = 12f
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(48, 59, 90), 12f)
            setOnClickListener { action() }
            contentDescription = subtitle
        }
        card.addView(button, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(8), 0, 0) })
    }

    private fun cardBase(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(Color.rgb(17, 24, 42), 18f, Color.rgb(48, 59, 90))
        layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, dp(10))
        }
    }

    private fun infoValue(value: String): TextView = label(value, 12f, Color.rgb(190, 198, 220)).apply {
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(Color.rgb(24, 34, 58), 12f)
    }

    private fun label(value: String, size: Float, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun darkSpinner(items: Array<String>, selected: Int): Spinner = Spinner(this).apply {
        adapter = object : ArrayAdapter<String>(this@SettingsCenterActivity, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(Color.WHITE)
                view.textSize = 13f
                view.setPadding(dp(12), dp(5), dp(12), dp(5))
                view.background = rounded(Color.rgb(24, 34, 58), 12f)
                return view
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(Color.WHITE)
                view.setBackgroundColor(Color.rgb(17, 24, 42))
                view.setPadding(dp(16), dp(12), dp(16), dp(12))
                return view
            }
        }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        setSelection(selected.coerceIn(0, items.lastIndex))
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, dp(4), 0, dp(8)) }
    }

    private fun simpleItemListener(onSelected: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        private var first = true
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            // Spinner emits once during construction. That emission is still a valid persisted state,
            // but ignoring it avoids an unnecessary full-screen refresh before the user touches the control.
            if (first) { first = false; return }
            onSelected(position)
        }
    }

    private fun addCardToContent(card: View, topMargin: Int) {
        rootContent.addView(card, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, topMargin, 0, dp(10))
        })
    }

    private fun openLegacySettings() {
        SettingsDialog.show(this, settings, SubtitleOverlay(this)) { refreshUi() }
    }

    private fun translationProviderLabel(): String = when (settings.translationProvider) {
        SubtitleSettings.TRANSLATION_GEMINI -> "Gemini"
        SubtitleSettings.TRANSLATION_OMNIROUTE -> "OmniRoute أولاً"
        else -> "تلقائي"
    }

    private fun translationStatusText(): String = when {
        settings.translationProvider == SubtitleSettings.TRANSLATION_GEMINI && settings.hasGeminiApiKey() -> "المفتاح محفوظ • الاتصال لم يُختبر في Stage 36"
        settings.translationProvider == SubtitleSettings.TRANSLATION_OMNIROUTE && settings.omniRouteEnabled && settings.hasOmniRouteApiKey() -> "مهيأ • الاتصال لم يُختبر في Stage 36"
        settings.translationProvider == SubtitleSettings.TRANSLATION_OMNIROUTE -> "يحتاج تفعيل OmniRoute ومفتاحه"
        else -> "الوضع التلقائي يحافظ على المسار الحالي"
    }

    private fun sttStatusText(): String = when (settings.sttProvider) {
        SubtitleSettings.STT_LOCAL -> "Vosk العربية: ${installed(VoskModelManager.ARABIC)} • الإنجليزية: ${installed(VoskModelManager.ENGLISH)}"
        SubtitleSettings.STT_DEEPGRAM -> "Deepgram: ${if (settings.hasDeepgramApiKey()) "المفتاح محفوظ" else "المفتاح غير محفوظ"}"
        SubtitleSettings.STT_OPENAI -> "OpenAI: ${if (settings.hasOpenAiApiKey()) "المفتاح محفوظ" else "المفتاح غير محفوظ"}"
        SubtitleSettings.STT_GROQ -> "Groq: ${if (settings.hasGroqApiKey()) "المفتاح محفوظ" else "المفتاح غير محفوظ"}"
        else -> "تلقائي: Groq → Deepgram → Vosk عند توفر الجاهزية"
    }

    private fun installed(model: VoskModelManager.ModelInfo): String =
        if (VoskModelManager.isInstalled(this, model)) "مثبت ✓" else "غير مثبت"

    private fun showPrivacyInfo() {
        AlertDialog.Builder(this)
            .setTitle("الخصوصية والأذونات")
            .setMessage("Stage 36 لا تضيف صلاحيات جديدة. مفاتيح API تبقى في مخازنها الآمنة الحالية. لا نعتبر وجود المفتاح اتصالًا ناجحًا، ولا نعرض الأسرار في مركز الإعدادات أو في سجل العمليات.")
            .setPositiveButton("حسنًا", null)
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("حول Sari Subtitle")
            .setMessage("Stage 36 — Premium Settings UI\n\nPackage: $packageName\nVersion: ${BuildConfig.VERSION_NAME}\nVersion code: ${BuildConfig.VERSION_CODE}\n\nتم تطوير واجهة الإعدادات فوق المشروع الحالي دون إنشاء مشروع جديد أو حذف إعدادات Stage 35.")
            .setPositiveButton("حسنًا", null)
            .show()
    }

    private fun transparentBackground(): GradientDrawable = rounded(Color.TRANSPARENT, 0f)

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

package com.sari.subtitle

import com.sari.subtitle.v2.R
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Translation provider settings; secrets are encrypted by their individual Keystore-backed stores. */
object TranslationProvidersDialog {
    /** End-user surface: provider names, model IDs, keys and routing policy stay out of sight. */
    fun show(context: Context, settings: SubtitleSettings) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 34, 42, 20)
        }
        val title = TextView(context).apply {
            text = "الترجمة التلقائية"
            textSize = 21f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 12)
        }
        root.addView(title)
        root.addView(TextView(context).apply {
            text = if (settings.omniRouteEnabled || settings.translationProvider == SubtitleSettings.TRANSLATION_OMNIROUTE)
                "التوجيه الذكي مفعّل. سيختار النظام المسار المتاح بحسب نوع المهمة وحالة الاتصال."
            else "سيستخدم التطبيق إعدادات الترجمة الحالية. يمكن تفعيل التوجيه الذكي من إعدادات الإدارة."
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 12)
        })
        root.addView(TextView(context).apply {
            text = "الخصوصية: عند استخدام الترجمة السحابية، قد يُرسل الصوت إلى خدمة تحويل الكلام إلى نص، وقد يُرسل النص المستخرج إلى خدمة ترجمة خارجية عبر بوابة OmniRoute أو حساباتك المرتبطة. لا تفعّل الخدمة للمحتوى الحساس إذا لم تكن موافقاً على هذه المعالجة."
            textSize = 12f
            setTextColor(0xFF8A4B08.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 8)
        })
        var taps = 0
        title.setOnClickListener {
            taps++
            if (taps >= 7) {
                taps = 0
                showAdminPanel(context, settings)
            }
        }
        title.setOnLongClickListener {
            showAdminPanel(context, settings)
            true
        }
        AlertDialog.Builder(context)
            .setView(root)
            .setPositiveButton("حسناً", null)
            .show()
    }

    /** Owner/admin configuration. Opened by a deliberate multi-tap on the title. */
    private fun showAdminPanel(context: Context, settings: SubtitleSettings) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 12)
        }
        root.addView(TextView(context).apply {
            text = "إعدادات إدارة البوابة الذكية"
            textSize = 21f; setTextColor(Color.BLACK); gravity = Gravity.START
        })
        root.addView(TextView(context).apply {
            text = "اربط حسابات OAuth ومفاتيح upstream من لوحة OmniRoute. يحتفظ التطبيق بعنوان البوابة ومفتاح Gateway فقط، ثم يرسل كل مهمة إلى المسار المتوافق معها. إعدادات المزودات المباشرة القديمة محفوظة للتوافق ولا تُعرض في واجهة الإدارة الجديدة."
            textSize = 13f; setTextColor(Color.DKGRAY); setPadding(0, 8, 0, 12)
        })

        root.addView(TextView(context).apply { text = "مزود الترجمة الأساسي"; textSize = 14f; setTextColor(Color.BLACK); visibility = android.view.View.GONE })
        val providerSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("تلقائي: Gemini ثم OmniRoute عند تفعيل البديل", "Gemini فقط (إلا إذا فُعّل البديل أدناه)", "OmniRoute أولًا"))
            setSelection(when (settings.translationProvider) {
                SubtitleSettings.TRANSLATION_GEMINI -> 1
                SubtitleSettings.TRANSLATION_OMNIROUTE -> 2
                else -> 0
            })
        }
        providerSpinner.visibility = android.view.View.GONE
        root.addView(providerSpinner)

        fun field(label: String, saved: Boolean): EditText {
            root.addView(TextView(context).apply {
                text = "$label  •  ${if (saved) "المفتاح محفوظ ✓" else "لم يُضف مفتاح بعد"}"
                textSize = 14f; setTextColor(if (saved) 0xFF237A3B.toInt() else Color.DKGRAY)
                setPadding(0, 8, 0, 2)
                visibility = android.view.View.GONE
            })
            return EditText(context).apply {
                hint = if (saved) "اكتب مفتاحًا جديدًا لتغييره" else "أدخل المفتاح (اختياري)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true); visibility = android.view.View.GONE; root.addView(this)
            }
        }
        val gemini = field("Gemini", settings.hasGeminiApiKey())
        val deepL = field("DeepL", settings.hasDeepLApiKey())
        val azure = field("Azure Translator", settings.hasAzureTranslatorKey())
        val region = EditText(context).apply {
            hint = "منطقة Azure (مثل eastus أو global)"; setSingleLine(true)
            setText(settings.azureTranslatorRegion); visibility = android.view.View.GONE; root.addView(this)
        }
        val hf = field("Hugging Face", settings.hasHuggingFaceToken())

        root.addView(TextView(context).apply {
            text = "OmniRoute المحلي (Termux)"; textSize = 16f; setTextColor(Color.BLACK); setPadding(0, 18, 0, 4)
        })
        root.addView(TextView(context).apply {
            text = "اربط حسابات OAuth ومفاتيح الخدمات من لوحة OmniRoute أولاً. التطبيق يتصل بالبوابة فقط ولا يحتاج إلى تخزين مفاتيح مزودي upstream. إذا كانت البوابة على الكمبيوتر فاستخدم عنوان LAN؛ أما 127.0.0.1 داخل Android فيشير إلى الهاتف نفسه. مفتاح البوابة يُحفظ مشفراً ولا يُضمّن في APK."
            textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, 2, 0, 6)
        })
        val omniEnabled = CheckBox(context).apply {
            text = "تفعيل التوجيه الذكي عبر البوابة"
            isChecked = settings.omniRouteEnabled || settings.translationProvider == SubtitleSettings.TRANSLATION_OMNIROUTE ||
                settings.sttProvider == SubtitleSettings.STT_OMNIROUTE
            root.addView(this)
        }
        root.addView(TextView(context).apply {
            text = "سياسة التوجيه التلقائي (تُطبق عندما يكون نموذج الترجمة auto)"
            textSize = 13f; setTextColor(Color.BLACK); setPadding(0, 8, 0, 2)
        })
        val routeMode = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, arrayOf(
                "حصة الاشتراك فقط — يمنع الانتقال إلى مسار مدفوع بالفاتورة",
                "تلقائي متوازن — قد يستهلك رصيد API مدفوعاً",
                "الأرخص — قد يستهلك رصيد API مدفوعاً"
            ))
            setSelection(when (settings.omniRouteRoutingMode) {
                "auto" -> 1
                "auto/cheap" -> 2
                else -> 0
            })
            root.addView(this)
        }
        val paidConsent = CheckBox(context).apply {
            text = "أسمح صراحةً باستخدام مسار قد يكون مدفوعاً (Auto / Cheap)"
            isChecked = settings.omniRoutePaidFallbackAllowed
            root.addView(this)
        }
        root.addView(TextView(context).apply {
            text = "تنبيه: وضع حصة الاشتراك لا يعني أن الخدمة مجانية؛ قد يستهلك حصة ضمن اشتراك مدفوع تملكه، لكنه مصمم لعدم التحول إلى طلب billable عند نفادها. أما Auto وCheap فلا يضمنان المجانية."
            textSize = 11f; setTextColor(0xFF8A4B08.toInt()); setPadding(0, 2, 0, 8)
        })
        val omniBase = EditText(context).apply {
            hint = "عنوان الأساس"; setSingleLine(true); setText(settings.omniRouteBaseUrl); root.addView(this)
        }
        val omniTextModel = EditText(context).apply {
            hint = "اسم نموذج الترجمة (auto افتراضيًا)"; setSingleLine(true); setText(settings.omniRouteTextModel); root.addView(this)
        }
        val omniSttModel = EditText(context).apply {
            hint = "اسم نموذج STT في OmniRoute"; setSingleLine(true); setText(settings.omniRouteSttModel); root.addView(this)
        }
        val omniKey = EditText(context).apply {
            hint = if (settings.hasOmniRouteApiKey()) "مفتاح OmniRoute محفوظ؛ أدخل قيمة جديدة لتغييره" else "مفتاح API (اختياري حسب إعداد الخادم)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true); root.addView(this)
        }
        fun addModelPickerButton(label: String, target: EditText, allowAuto: Boolean) {
            root.addView(android.widget.Button(context).apply {
                text = label
                setOnClickListener {
                    isEnabled = false
                    val button = this
                    Thread {
                        val result = runCatching {
                            settings.omniRouteBaseUrl = omniBase.text.toString().trim().ifBlank { "http://127.0.0.1:20128/v1" }
                            val enteredKey = omniKey.text.toString().trim()
                            if (enteredKey.isNotEmpty()) settings.omniRouteApiKey = enteredKey
                            OmniRouteTranslationClient(settings).availableModels()
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            button.isEnabled = true
                            val models: List<OmniRouteModel> = result.getOrElse {
                                Toast.makeText(context, "تعذر جلب النماذج: ${it.message ?: "خطأ"}", Toast.LENGTH_LONG).show()
                                emptyList()
                            }
                            if (models.isNotEmpty()) {
                                // Catalog entries with explicit free metadata are shown first; unknown is never labeled free.
                                val orderedModels = models.sortedWith(
                                    compareBy<OmniRouteModel>(
                                        { when (it.isFree) { true -> 0; null -> 1; false -> 2 } },
                                        { if (allowAuto) if (it.capabilities.any { c -> c in setOf("chat", "text", "completion", "completions") }) 0 else 1
                                          else if (it.capabilities.any { c -> c in setOf("audio", "stt", "transcription", "speech-to-text") }) 0 else 1 },
                                        { it.id.lowercase() }
                                    )
                                )
                                val labels = (if (allowAuto) listOf("auto — تطبيق سياسة التوجيه المحددة أدناه") else emptyList()) +
                                    orderedModels.map { model ->
                                        buildString {
                                            append(model.id)
                                            if (model.ownedBy.isNotBlank()) append(" • ").append(model.ownedBy)
                                            append(" • ").append(model.costLabel)
                                            append(" • ").append(model.capabilityLabel)
                                            append(" • ").append(model.authLabel)
                                        }
                                    }
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("النماذج التي يعرضها OmniRoute")
                                    .setItems(labels.toTypedArray()) { _, which ->
                                        target.setText(if (allowAuto && which == 0) "auto" else orderedModels[which - if (allowAuto) 1 else 0].id)
                                    }
                                    .setNegativeButton("إلغاء", null)
                                    .show()
                            } else if (result.isSuccess) {
                                Toast.makeText(context, "لم يُرجع OmniRoute نماذج متاحة.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }.start()
                }
            })
        }
        addModelPickerButton("اكتشاف واختيار نموذج الترجمة من OmniRoute", omniTextModel, true)
        addModelPickerButton("اكتشاف واختيار نموذج STT من OmniRoute", omniSttModel, false)
        root.addView(TextView(context).apply {
            text = "ترتيب Free-first: النماذج التي يعلن الخادم صراحةً أنها مجانية تظهر أولاً، ثم النماذج مجهولة السعر، ثم غير المجانية. هذا ترتيب للاختيار اليدوي وليس ضماناً بأن auto سيستخدم مجاناً. لا نخمن السعر أو القدرات من الاسم. ظهور النموذج لا يعني أن حسابك موثّق أو أن الحصة متاحة. قائمة /models لا تثبت دعم STT؛ يجب التحقق من /audio/transcriptions."
            textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, 6, 0, 8)
        })
        root.addView(TextView(context).apply {
            text = if (settings.hasOmniRouteApiKey()) "حالة مفتاح OmniRoute: محفوظ ✓" else "حالة مفتاح OmniRoute: غير مضبوط (قد يكون اختياريًا)"
            textSize = 12f; setTextColor(if (settings.hasOmniRouteApiKey()) 0xFF237A3B.toInt() else Color.DKGRAY)
        })
        val testButton = android.widget.Button(context).apply {
            text = "اختبار اتصال البوابة واكتشاف النماذج"
        }
        root.addView(testButton)
        testButton.setOnClickListener {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val enteredBase = omniBase.text.toString().trim().ifBlank { "http://127.0.0.1:20128/v1" }
            val enteredKey = omniKey.text.toString().trim()
            val enteredModel = omniTextModel.text.toString().trim().ifBlank { "auto" }
            testButton.isEnabled = false
            testButton.text = "جاري الاتصال، قد يستغرق 30 ثانية..."
            Thread {
                val result = try {
                    settings.omniRouteBaseUrl = enteredBase
                    settings.omniRouteTextModel = enteredModel
                    if (enteredKey.isNotEmpty()) settings.omniRouteApiKey = enteredKey
                    OmniRouteTranslationClient(settings).testConnection()
                } catch (e: Exception) { "تعذر الاتصال: ${e.message ?: "خطأ غير معروف"}" }
                mainHandler.post {
                    testButton.isEnabled = true
                    testButton.text = "اختبار اتصال البوابة واكتشاف النماذج"
                    Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        root.addView(TextView(context).apply {
            text = "لن يتغير كود التزامن أو حفظ واستعادة SRT. يرسل OmniRoute نصوص الترجمة فقط، ولا يرسل ملف الصوت أو توقيتات SRT."
            textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, 10, 0, 4)
        })

        val scroll = android.widget.ScrollView(context).apply { addView(root) }
        AlertDialog.Builder(context)
            .setView(scroll)
            .setPositiveButton("حفظ الإعدادات") { _, _ ->
                try {
                    val selectedRoutingMode = when (routeMode.selectedItemPosition) {
                        1 -> "auto"
                        2 -> "auto/cheap"
                        else -> "auto/subscription"
                    }
                    if (selectedRoutingMode != "auto/subscription" && !paidConsent.isChecked) {
                        throw IllegalStateException("للسماح بوضع Auto أو Cheap يجب تفعيل موافقة المسار المدفوع صراحةً، أو اختر حصة الاشتراك فقط.")
                    }
                    settings.translationProvider = if (omniEnabled.isChecked) SubtitleSettings.TRANSLATION_OMNIROUTE else when (providerSpinner.selectedItemPosition) {
                        1 -> SubtitleSettings.TRANSLATION_GEMINI
                        2 -> SubtitleSettings.TRANSLATION_OMNIROUTE
                        else -> SubtitleSettings.TRANSLATION_AUTO
                    }
                    settings.omniRouteEnabled = omniEnabled.isChecked || settings.sttProvider == SubtitleSettings.STT_OMNIROUTE
                    gemini.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.geminiApiKey = it }
                    deepL.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.deepLApiKey = it }
                    azure.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.azureTranslatorKey = it }
                    settings.azureTranslatorRegion = region.text.toString().trim().ifBlank { "global" }
                    hf.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.huggingFaceToken = it }
                    settings.omniRouteBaseUrl = omniBase.text.toString().trim().ifBlank { "http://127.0.0.1:20128/v1" }
                    settings.omniRouteTextModel = omniTextModel.text.toString().trim().ifBlank { "auto" }
                    settings.omniRouteSttModel = omniSttModel.text.toString().trim().ifBlank { "whisper-large-v3-turbo" }
                    settings.omniRouteRoutingMode = selectedRoutingMode
                    settings.omniRoutePaidFallbackAllowed = paidConsent.isChecked
                    omniKey.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.omniRouteApiKey = it }
                    Toast.makeText(context, "تم حفظ إعدادات الترجمة وOmniRoute", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "تعذر حفظ الإعدادات: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }
}

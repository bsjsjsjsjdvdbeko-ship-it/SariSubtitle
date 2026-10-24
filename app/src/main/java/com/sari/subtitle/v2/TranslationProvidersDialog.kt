package com.sari.subtitle.v2

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
    fun show(context: Context, settings: SubtitleSettings) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 12)
        }
        root.addView(TextView(context).apply {
            text = "إعداد مزودات الترجمة"
            textSize = 21f; setTextColor(Color.BLACK); gravity = Gravity.START
        })
        root.addView(TextView(context).apply {
            text = "اختر المزود الأساسي: عند اختيار OmniRoute أولاً يبدأ به التطبيق؛ وعند تفعيله كبديل يبدأ بـ Gemini ثم يجرب OmniRoute عند فشل Gemini. بعدهما تُجرّب بدائل الترجمة النصية المتاحة. Vosk للتفريغ الصوتي فقط وليس مزود ترجمة. اترك حقول المفاتيح فارغة للاحتفاظ بالقيم المحفوظة."
            textSize = 13f; setTextColor(Color.DKGRAY); setPadding(0, 8, 0, 12)
        })

        root.addView(TextView(context).apply { text = "مزود الترجمة الأساسي"; textSize = 14f; setTextColor(Color.BLACK) })
        val providerSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("تلقائي: Gemini ثم OmniRoute عند تفعيل البديل", "Gemini فقط (إلا إذا فُعّل البديل أدناه)", "OmniRoute أولًا"))
            setSelection(when (settings.translationProvider) {
                SubtitleSettings.TRANSLATION_GEMINI -> 1
                SubtitleSettings.TRANSLATION_OMNIROUTE -> 2
                else -> 0
            })
        }
        root.addView(providerSpinner)

        fun field(label: String, saved: Boolean): EditText {
            root.addView(TextView(context).apply {
                text = "$label  •  ${if (saved) "المفتاح محفوظ ✓" else "لم يُضف مفتاح بعد"}"
                textSize = 14f; setTextColor(if (saved) 0xFF237A3B.toInt() else Color.DKGRAY)
                setPadding(0, 8, 0, 2)
            })
            return EditText(context).apply {
                hint = if (saved) "اكتب مفتاحًا جديدًا لتغييره" else "أدخل المفتاح (اختياري)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true); root.addView(this)
            }
        }
        val gemini = field("Gemini", settings.hasGeminiApiKey())
        val deepL = field("DeepL", settings.hasDeepLApiKey())
        val azure = field("Azure Translator", settings.hasAzureTranslatorKey())
        val region = EditText(context).apply {
            hint = "منطقة Azure (مثل eastus أو global)"; setSingleLine(true)
            setText(settings.azureTranslatorRegion); root.addView(this)
        }
        val hf = field("Hugging Face", settings.hasHuggingFaceToken())

        root.addView(TextView(context).apply {
            text = "OmniRoute المحلي (Termux)"; textSize = 16f; setTextColor(Color.BLACK); setPadding(0, 18, 0, 4)
        })
        root.addView(TextView(context).apply {
            text = "شغّل OmniRoute على الهاتف نفسه. العنوان الافتراضي يتصل بـ 127.0.0.1:20128. مفتاح الوصول اختياري إذا كان الخادم لا يطلب مصادقة؛ وإذا أدخلته يُحفظ مشفرًا ولا يُضمّن في APK."
            textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, 2, 0, 6)
        })
        val omniEnabled = CheckBox(context).apply {
            text = "تفعيل OmniRoute كبديل تلقائي عند فشل Gemini"
            isChecked = settings.omniRouteEnabled || settings.translationProvider == SubtitleSettings.TRANSLATION_OMNIROUTE
            root.addView(this)
        }
        val omniBase = EditText(context).apply {
            hint = "عنوان الأساس"; setSingleLine(true); setText(settings.omniRouteBaseUrl); root.addView(this)
        }
        val omniTextModel = EditText(context).apply {
            hint = "اسم نموذج الترجمة (auto افتراضيًا)"; setSingleLine(true); setText(settings.omniRouteTextModel); root.addView(this)
        }
        val omniKey = EditText(context).apply {
            hint = if (settings.hasOmniRouteApiKey()) "مفتاح OmniRoute محفوظ؛ أدخل قيمة جديدة لتغييره" else "مفتاح API (اختياري حسب إعداد الخادم)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true); root.addView(this)
        }
        root.addView(TextView(context).apply {
            text = if (settings.hasOmniRouteApiKey()) "حالة مفتاح OmniRoute: محفوظ ✓" else "حالة مفتاح OmniRoute: غير مضبوط (قد يكون اختياريًا)"
            textSize = 12f; setTextColor(if (settings.hasOmniRouteApiKey()) 0xFF237A3B.toInt() else Color.DKGRAY)
        })
        val testButton = android.widget.Button(context).apply {
            text = "اختبار اتصال OmniRoute (/v1/models)"
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
                    testButton.text = "اختبار اتصال OmniRoute (/v1/models)"
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
                    settings.translationProvider = when (providerSpinner.selectedItemPosition) {
                        1 -> SubtitleSettings.TRANSLATION_GEMINI
                        2 -> SubtitleSettings.TRANSLATION_OMNIROUTE
                        else -> SubtitleSettings.TRANSLATION_AUTO
                    }
                    settings.omniRouteEnabled = omniEnabled.isChecked || settings.translationProvider == SubtitleSettings.TRANSLATION_OMNIROUTE
                    gemini.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.geminiApiKey = it }
                    deepL.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.deepLApiKey = it }
                    azure.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.azureTranslatorKey = it }
                    settings.azureTranslatorRegion = region.text.toString().trim().ifBlank { "global" }
                    hf.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.huggingFaceToken = it }
                    settings.omniRouteBaseUrl = omniBase.text.toString().trim().ifBlank { "http://127.0.0.1:20128/v1" }
                    settings.omniRouteTextModel = omniTextModel.text.toString().trim().ifBlank { "auto" }
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

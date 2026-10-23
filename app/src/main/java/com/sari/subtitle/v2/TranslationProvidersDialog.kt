package com.sari.subtitle.v2

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** A focused, compact screen for translation provider keys. The router selects fallbacks automatically. */
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
            text = "اترك أي حقل فارغًا للاحتفاظ بالمفتاح المحفوظ. يعمل التبديل تلقائيًا: Gemini، ثم المزودات الاحتياطية المتاحة. مفاتيحك تُحفظ مشفرة على الجهاز."
            textSize = 13f; setTextColor(Color.DKGRAY); setPadding(0, 8, 0, 12)
        })
        fun field(label: String, saved: Boolean): EditText {
            root.addView(TextView(context).apply {
                text = "$label  •  ${if (saved) "المفتاح محفوظ" else "لم يُضف مفتاح بعد"}"
                textSize = 14f; setTextColor(if (saved) 0xFF237A3B.toInt() else Color.DKGRAY)
                setPadding(0, 8, 0, 2)
            })
            return EditText(context).apply {
                hint = if (saved) "اكتب مفتاحًا جديدًا لتغييره" else "أدخل المفتاح (اختياري)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                root.addView(this)
            }
        }
        val gemini = field("Gemini", settings.hasGeminiApiKey())
        val deepL = field("DeepL", settings.hasDeepLApiKey())
        val azure = field("Azure Translator", settings.hasAzureTranslatorKey())
        val region = EditText(context).apply {
            hint = "منطقة Azure (مثل eastus أو global)"
            setSingleLine(true); setText(settings.azureTranslatorRegion); root.addView(this)
        }
        val hf = field("Hugging Face", settings.hasHuggingFaceToken())
        root.addView(TextView(context).apply {
            text = "البدائل الإضافية تعتمد على توفر الخدمة والحصة والنموذج. Google Translate وMyMemory موجودان ضمن المسار الاحتياطي الحالي. لا توجد حصة سحابية غير محدودة مضمونة."
            textSize = 12f; setTextColor(Color.DKGRAY); setPadding(0, 10, 0, 4)
        })
        val scroll = android.widget.ScrollView(context).apply { addView(root) }
        AlertDialog.Builder(context)
            .setView(scroll)
            .setPositiveButton("حفظ المفاتيح") { _, _ ->
                try {
                    gemini.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.geminiApiKey = it }
                    deepL.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.deepLApiKey = it }
                    azure.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.azureTranslatorKey = it }
                    settings.azureTranslatorRegion = region.text.toString().trim().ifBlank { "global" }
                    hf.text.toString().trim().takeIf { it.isNotEmpty() }?.let { settings.huggingFaceToken = it }
                    Toast.makeText(context, "تم حفظ إعدادات مزودات الترجمة", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "تعذر حفظ المفتاح: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }
}

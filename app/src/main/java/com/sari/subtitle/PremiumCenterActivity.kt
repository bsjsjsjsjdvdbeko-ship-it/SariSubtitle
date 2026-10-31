package com.sari.subtitle

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Independent destination for the home-screen Premium button. */
class PremiumCenterActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(18), dp(18), dp(18), dp(20))
            setBackgroundColor(Color.rgb(8, 11, 22))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(Button(this).apply {
            text = "رجوع"
            isAllCaps = false
            setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply {
            text = "النسخة المميزة"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), 0, 0, 0)
        })
        root.addView(top)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(18), 0, dp(20))
        }
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(25, 40, 82), Color.rgb(82, 37, 132), Color.rgb(17, 24, 42))
            ).apply { cornerRadius = dp(22).toFloat(); setStroke(dp(1), Color.rgb(170, 120, 255)) }
        }
        hero.addView(TextView(this).apply {
            text = "♛  Sari Subtitle Premium"
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        hero.addView(TextView(this).apply {
            text = "مساحة مستقلة لمميزات النسخة المميزة وإدارة الاشتراك."
            textSize = 14f
            setTextColor(Color.rgb(225, 220, 245))
            setPadding(0, dp(10), 0, 0)
        })
        content.addView(hero)

        addInfo(content, "مميزات التطبيق", "الوصول إلى أدوات الترجمة وإدارة الملفات وسجل العمليات ومراكز مزوّدي الخدمات.")
        addInfo(content, "حالة الاشتراك", "لا توجد في ملفات هذا المشروع بوابة دفع أو تحقق اشتراك مفعّلة؛ لذلك لن نعرض زر شراء وهميًا أو ندّعي تفعيل اشتراك.")
        addInfo(content, "إدارة الإعدادات", "الإعدادات لها صفحة مستقلة. استخدم زر الرجوع ثم زر الإعدادات من الصفحة الرئيسية.")

        content.addView(Button(this).apply {
            text = "العودة إلى الصفحة الرئيسية"
            isAllCaps = false
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(12) })

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun addInfo(parent: LinearLayout, title: String, body: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = GradientDrawable().apply {
                setColor(Color.rgb(17, 24, 42)); cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(48, 59, 90))
            }
        }
        card.addView(TextView(this).apply {
            text = title; textSize = 17f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = body; textSize = 13f; setTextColor(Color.rgb(190, 198, 220))
            setPadding(0, dp(7), 0, 0)
        })
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

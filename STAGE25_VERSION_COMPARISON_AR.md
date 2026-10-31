# تقرير مقارنة النسخ — Sari Subtitle Stage 25

## النسخ التي تمت مقارنتها

| النسخة | SHA-256 | عدد الملفات بعد الاستخراج |
|---|---|---:|
| `SariSubtitle_Stage25_SmartHistory_StablePlayer_CompileFix.zip` | `ec1618a65718a23044fdb63813d4080d23a47d0a5b6a4cf9a72430d5f3e4e997` | 78 |
| `SariSubtitle_Stage25_EngineeringAuditFix.zip` | `a90c2a0b4e0777145a602625712b19d7fef8dca080d847fa70eb77d9919acb2a` | 79 |
| `SariSubtitle_Stage25_EngineeringAudit_FullReview.zip` | `b4a49a2e1a8e19e06b566ae62f78469f90340d228d08ffa0369fb8e64935b02e` | 80 |

## الفروقات المثبتة

### من النسخة المرجعية إلى EngineeringAuditFix

- أضيف تقرير `STAGE25_ENGINEERING_AUDIT_AR.md`.
- تغيّر منطق `SariSubtitleActivity.kt` و`SrtStorage.kt`.
- بقية الملفات، بعد تجاهل المسارات، لم تتغير محتوياتها البرمجية في هذه المقارنة.

### من EngineeringAuditFix إلى FullReview

- أضيف `VideoUrlIdentity.kt` لتوحيد تطبيع/مطابقة روابط الفيديو.
- نُقلت ملفات Kotlin من المسار `com/sari/subtitle/v2` إلى `com/sari/subtitle` وتغيرت تصريحات `package`.
- تغيّر منطق إضافي في `SariSubtitleActivity.kt` و`SrtStorage.kt` و`SrtParser.kt` و`DeepLTranslationService.kt` و`LanguageDetector.kt` و`SubWhisperInternalActivity.kt`.
- تغيرت إعدادات Gradle وسير GitHub Actions ومرجع `SubtitleOverlay` داخل `activity_main.xml`.
- أضيف `REPAIR_REPORT_AR.md`.

## تعارض هوية التطبيق — يحتاج انتباهًا قبل التثبيت

النسخة المرجعية التي رُفعت في هذه المحادثة تعرّف `applicationId` و`namespace` على `com.sari.subtitle.v2`. نسخة FullReview غيّرتهما إلى `com.sari.subtitle` استجابةً لقيد الحفاظ على هوية المشروع السابق. تغيير `applicationId` يجعل Android يتعامل مع النسخة كتطبيق مختلف، ولا يحدّث التطبيق المثبت تحت الحزمة الأخرى، كما لا يتيح لها الوصول مباشرةً إلى مجلد بيانات التطبيق القديم. لذلك يجب التأكد من الحزمة المثبتة على الهاتف قبل تثبيت APK النهائي؛ لا يمكن استنتاج الحزمة المثبتة من ZIP وحده.

## فحوصات ساكنة نُفذت على FullReview

- 80 ملفًا بعد الاستخراج، منها 34 ملف Kotlin و35 ملف Markdown.
- 7,769 سطرًا تقريبًا في ملفات Kotlin.
- تم تحليل ملفات XML الخمسة بنجاح.
- 30 مرجعًا إلى `R.id`، ولم يظهر أي معرّف مفقود ضمن موارد XML المفحوصة.
- 9 ثوابت لطلبات Activity Result، ولا توجد أرقام مكررة.
- لم يظهر تعريف مكرر لـ `sameVideoUrl`.
- لم تبقَ مراجع `com.sari.subtitle.v2` في ملفات المصدر النشطة وإعدادات التطبيق داخل FullReview.
- لم تكن هناك مجلدات `app/src/test` أو `app/src/androidTest` في FullReview قبل هذه المرحلة.

## حدود المقارنة

المقارنة الرقمية والساكنة لا تثبت أن كل ميزة تعمل. لا يوجد في الأرشيف سجل Logcat من لحظة انهيار «ملفاتي»، ولا يمكن تأكيد السبب الجذري لهذا الانهيار دون إعادة إنتاجه أو توفر Stack Trace.

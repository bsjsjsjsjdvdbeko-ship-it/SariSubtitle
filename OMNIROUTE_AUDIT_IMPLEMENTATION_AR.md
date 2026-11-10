# تقرير مراجعة وتنفيذ OmniRoute — Sari Subtitle v10

## نطاق العمل
تم العمل على أحدث أرشيف متاح في المحادثة: `SariSubtitle_v10_MyFiles_SRT_Download.zip`، مع تعديل المشروع نفسه وعدم إنشاء مشروع Android جديد.

## ما كان موجوداً قبل التعديل
- `OmniRouteTranslationClient` يرسل ترجمة النصوص إلى `/v1/chat/completions`.
- إعداد `omniRouteTextModel` محفوظ من شاشة مزودي الترجمة.
- مفتاح OmniRoute محفوظ عبر `OmniRouteApiKeyStore` باستخدام Android Keystore AES-GCM.
- يوجد توجيه متعدد المزودين وقياس latency/quality وFallback في `ProviderDecisionEngine` و`ProviderDecisionFailoverController`.
- يوجد تكيّف حسب نوع الشبكة وحجم مقطع الصوت في `AdaptiveRoutingEngine`.
- STT الحي الحالي يوجّه إلى Groq وDeepgram وOpenAI مع Vosk كخيار محلي؛ لا يوجد ربط فعلي لـ OmniRoute STT في `LiveSttRouter` بهذه النسخة.

## تعديلات تم تنفيذها

### 1) إصلاح تجاهل نموذج الترجمة المحدد
كان `OmniRouteTranslationClient` يثبت النموذج على قيمة hard-coded ويتجاهل `settings.omniRouteTextModel` في المسار الأساسي.

أصبح الاختيار:
- `modelOverride` إذا مررته العملية صراحةً.
- وإلا يستخدم `settings.omniRouteTextModel` المحفوظ.
- وإذا لم توجد قيمة يستخدم `auto`.

### 2) تحسين اختبار الاتصال
أصبح `testConnection()`:
- يستعلم عن `/v1/models`.
- يعرض عدد النماذج والنموذج المحدد.
- يرفض النموذج المحدد إذا لم يكن `auto` ولم يظهر ضمن قائمة النماذج المتاحة (عندما يعيد الخادم قائمة غير فارغة).

### 3) إصلاح HALF_OPEN ليكون مسباراً واحداً
كان انتهاء مدة OPEN يجعل المزود في حالة HALF_OPEN، لكن لم يكن هناك قفل يمنع عدة مقاطع ترجمة متزامنة من تجربة المزود المتعافي في الوقت نفسه.

أضيفت آلية probe lease متزامنة لكل مزود:
- يسمح بطلب اختبار واحد فقط عندما تكون الدائرة HALF_OPEN.
- الطلبات المتزامنة الأخرى تتجاوز هذا المزود مؤقتاً وتكمل إلى المرشح التالي.
- يتم تحرير القفل في `finally` سواء نجح الطلب أو فشل أو رُفضت الجودة.
- نجاح المسبار يعيد حالة المزود إلى READY/CLOSED عبر `recordSuccess`، والفشل يحدّث حالة الدائرة عبر `recordFailure`.

## الملفات المعدلة
- `app/src/main/java/com/sari/subtitle/OmniRouteTranslationClient.kt`
- `app/src/main/java/com/sari/subtitle/ProviderRoutingEngine.kt`
- `app/src/main/java/com/sari/subtitle/ProviderDecisionFailoverController.kt`

## ما لم أغيّره عمداً
- لم أضف OmniRoute إلى STT الحي؛ لأن النسخة الحالية لا تحتوي على إعداد نموذج STT خاص بـ OmniRoute ولا adapter multipart مربوط بدورة حياة `LiveSttRouter`. إضافة ذلك تتطلب تنفيذ endpoint `/v1/audio/transcriptions` واختبار توافق نموذج الصوت الفعلي، لا مجرد إضافة اسم مزود إلى القائمة.
- لم أزعم أن الترجمة الحية تستخدم SSE؛ العميل الحالي يقرأ استجابة Chat Completions كاملة ولا يفسر SSE.
- لم أغير مفاتيح API أو عناوين الخادم أو إعدادات المزودين الحالية.

## التحقق
- تم فحص بنية المشروع ووجود الملفات المطلوبة.
- تم التحقق من التعديلات النصية ومراجع الدوال وعدم بقاء اسم الثابت القديم.
- تم إنشاء ZIP وفحص CRC لكل محتوياته.
- لم يتم بناء APK في هذه البيئة: لا يوجد `gradlew` داخل الأرشيف ولا تثبيت Gradle محلي متاح. يجب تشغيل workflow الموجود في GitHub Actions أو بناء المشروع في بيئة Android/Gradle قبل اعتماد APK.

## النتيجة
هذا تحديث فعلي لتحسين توجيه OmniRoute الحالي: اختيار النموذج المحفوظ أصبح نافذاً، اختبار الاتصال يتحقق من توفره، وHALF_OPEN أصبح يطبق مسباراً واحداً متزامناً. لا يعني ذلك أن OmniRoute أصبح محرك STT أو Streaming حقيقياً في هذه النسخة.

# Sari Subtitle — Stage 13+ Audit & Stabilization Report

## نطاق التدقيق
تم فحص نسخة `SariSubtitle-Stage12-OmniRoute-UX-v7.zip` فعليًا من الملفات المصدرية، وليس الاعتماد على README أو تقارير المراحل السابقة.

## نتيجة التدقيق

| الميزة | الملف/المكوّن | الحالة الفعلية | الملاحظة | إثبات/اختبار |
|---|---|---|---|---|
| Android project structure | Gradle + app | IMPLEMENTED_BUT_UNVERIFIED | AGP 8.7.3 / Kotlin 2.0.21 / compileSdk 35، لكن لا يوجد Gradle Wrapper ولا Android SDK في بيئة التدقيق | فحص الملفات؛ build غير قابل للتنفيذ هنا |
| MediaProjection capture | AudioCaptureService | IMPLEMENTED_BUT_UNVERIFIED | المسار موجود مع foreground service وAudioPlaybackCapture | فحص المصدر/Manifest |
| Audio chunking | AudioChunker | PARTIALLY_IMPLEMENTED | 2s window + 400ms overlap؛ ما زال يعتمد على RMS ثابت 700 وقد يسقط الكلام الهادئ | مراجعة الكود |
| Auto language | Settings/TranslationRepository/WhisperClient | PARTIALLY_IMPLEMENTED | تم تصحيح default STT إلى `auto` في Stage 13؛ لا يزال اختيار اللغة من المصدر يحتاج اختبارًا فعليًا مع مزود STT | فحص الكود |
| Groq STT | WhisperClient | IMPLEMENTED_BUT_UNVERIFIED | endpoint/model/verbose_json موجودة؛ لا يوجد اختبار شبكة فعلي بالمفتاح | فحص الكود + الوثائق الرسمية |
| OmniRoute STT/translation | OmniRouteClient | IMPLEMENTED_BUT_UNVERIFIED | OpenAI-compatible calls موجودة؛ صحة endpoints الخاصة بالإدارة تعتمد على نسخة OmniRoute المستخدمة | فحص الكود فقط |
| Provider scoring | AiOrchestration | IMPLEMENTED_BUT_UNVERIFIED | يعتمد على history حقيقية، لكنه لا يملك dataset دقة ذهبية داخل التطبيق | فحص المعادلات والمسار |
| Adaptive noise/speed routing | AiOrchestration | PARTIALLY_IMPLEMENTED | noise/speed buckets موجودة، لكن العلاقة مع WER لم تُثبت تجريبيًا | فحص الكود |
| Circuit breaker | ProviderMetrics + CircuitBreaker | IMPLEMENTED_BUT_UNVERIFIED | النسخة السابقة كانت OPEN/CLOSED فقط؛ Stage 13 أضاف HALF_OPEN probe gate | اختبار مصدر/اختبار وحدة محدود؛ build Android غير متاح |
| Retry policy | STT/translation clients | PARTIALLY_IMPLEMENTED | توجد retries، لكنها ليست سياسة موحدة بالكامل ولا تحترم Retry-After في كل المسارات | فحص الكود |
| Hedged requests | AiOrchestration | PARTIALLY_IMPLEMENTED | المقارنة موجودة، لكنها اختيارية؛ تم جعلها OFF افتراضيًا لأن تشغيل طلبين دائمًا يضاعف التكلفة/الضغط | فحص الإعدادات |
| Translation context | EpisodeContext/TranslationRepository | IMPLEMENTED_BUT_UNVERIFIED | السياق والذاكرة والقاموس موجودة | فحص المصدر + اختبارات الوحدة الحالية |
| Streaming transport | OmniRouteClient | PARTIALLY_IMPLEMENTED | يجمع SSE ويقيس first-token/inter-token؛ لا توجد partial subtitles تصل للواجهة أثناء البث | فحص call path |
| Subtitle ordering | LiveSubtitleBus | IMPLEMENTED_BUT_UNVERIFIED | يمنع نتيجة قديمة من استبدال مقطع أحدث وفق ساعة الالتقاط | فحص المصدر |
| Overlay | OverlayService | PARTIALLY_IMPLEMENTED | RTL موجود؛ overlay غير قابل للمس حاليًا رغم وجود كود drag، لذلك drag ليس مسارًا فعليًا | فحص flags والكود |
| Dashboard | ProviderDashboardActivity | PARTIALLY_IMPLEMENTED | يعرض قياسات حقيقية/UNKNOWN؛ لكن عداد fallback في النسخة السابقة كان يحسب كل failure كـ fallback، وتم تصحيحه في Stage 13 | مراجعة الكود |
| Quota/rate-limit | OmniRoute metadata/runtime | IMPLEMENTED_BUT_UNVERIFIED | UNKNOWN يبقى null؛ لا يوجد إثبات حي من upstream في بيئة التدقيق | فحص المصدر |
| Security storage | EncryptedSharedPreferences | IMPLEMENTED_BUT_UNVERIFIED | مفاتيح الإعدادات مشفرة محليًا؛ لا يوجد secret hard-coded ظاهر في المصدر | static scan |
| API key exposure in logs | source scan | IMPLEMENTED_BUT_UNVERIFIED | لم يظهر تسجيل مباشر للمفاتيح؛ لا يوجد runtime log audit | static scan |
| Unit tests | app/src/test | IMPLEMENTED_BUT_UNVERIFIED | توجد اختبارات للمحللات والسياق والجودة والمنصة؛ لا يوجد اختبار كامل للمسار الشبكي | file inspection |
| E2E live test | device + real providers | BLOCKED_BY_EXTERNAL_SERVICE | لا يوجد جهاز Android/Android SDK/Gradle environment متاح في جلسة التدقيق | لم يُنفذ |
| WER/CER benchmark | benchmark dataset | MISSING | لا توجد corpus مرجعية داخل المشروع لحساب WER/CER الحقيقي | فحص الاختبارات |

## إصلاحات Stage 13 المنفذة فعليًا

1. تغيير الإعداد الافتراضي للمزوّد من MyMemory إلى Groq.
2. تغيير default `speechLanguage` من العربية إلى `auto` حتى لا يفرض التطبيق العربية على صوت إنجليزي أو لغة أخرى.
3. جعل Hedged Failover غير مفعّل افتراضيًا لتجنب مضاعفة الطلبات في كل جلسة.
4. إضافة `CircuitState` صريح: `CLOSED`, `OPEN`, `HALF_OPEN` مع probe واحد عند الانتقال إلى HALF_OPEN.
5. ربط مسار اختيار المزوّد بالـ circuit controller بدل الاعتماد على boolean OPEN فقط.
6. إصلاح عداد Dashboard الذي كان يساوي failures مع fallback events.
7. إصلاح استدعاءات `suspend` داخل `map` في Decision/Health snapshot بتحويلها إلى loops متوافقة مع Kotlin coroutines.
8. إضافة اختبار وحدة بسيط لوجود حالات Circuit Breaker الثلاث.

## ما لم أدّعِ نجاحه

- لم يتم الادعاء بأن APK تم بناؤه.
- لم يتم الادعاء بأن الترجمة الحية اختُبرت على جهاز فعلي.
- لم يتم الادعاء بتحقيق WER/CER أو latency P50/P95.
- لم يتم اعتبار noise/speech-speed routing مثبتًا من ناحية الدقة؛ هو routing مبني على بيانات تشغيلية/heuristics ويحتاج benchmark مرجعي.
- لم يتم اعتبار OmniRoute management endpoints مثبتة ضد خادم OmniRoute فعلي في هذه البيئة.

## اختبارات نفذت فعليًا في بيئة التدقيق

- ZIP integrity: ناجح.
- XML parsing لجميع ملفات XML: ناجح.
- static source inspection: ناجح.
- فحص وجود Gradle wrapper: غير موجود.
- فحص `gradle`: غير متوفر.
- فحص Android SDK: غير متوفر.
- لذلك Android assemble/unit test runner الكامل: BLOCKED.

## الخلاصة

النسخة السابقة لم تكن تستحق وصف "مكتملة 100%". توجد مكونات كثيرة حقيقية، لكن بعض الميزات كانت جزئية أو غير مثبتة، وكان هناك عيب Kotlin مهم في استدعاء suspend functions داخل `map`، إضافة إلى defaults لا تتفق مع Auto Detect، وCircuit Breaker غير مكتمل من ناحية HALF_OPEN.

Stage 13 هنا هو **Stabilization build** وليس ادعاء Release نهائي. الخطوة التالية الصحيحة هي توفير بيئة Android build/device أو تشغيل البناء على جهاز التطوير، ثم معالجة أي compile/runtime failures قبل إضافة تعقيد جديد.

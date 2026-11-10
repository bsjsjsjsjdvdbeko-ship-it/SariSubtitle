# Sari Subtitle — STT Provider Benchmark Phase 4

## ما أُضيف
- شاشة مستقلة `SttProviderBenchmarkActivity` مرتبطة من Provider Benchmark Center.
- اختيار ملف صوت/فيديو من مستندات Android عبر Storage Access Framework؛ لا يطلب التطبيق صلاحية تخزين شاملة.
- تشغيل الملف نفسه بالتتابع على كل مزود STT مضبوط من: Groq Whisper، Deepgram Nova-3، وOmniRoute.
- اختيار اللغة: اكتشاف تلقائي، العربية، أو الإنجليزية.
- قياس زمن الطلب الفعلي لكل مزود بواسطة `elapsedRealtime`.
- عند إدخال نص تفريغ صحيح مرجعي، يحسب WER وCER بخوارزمية Levenshtein، مع تطبيع أشكال الألف والتشكيل العربي. إذا لم يُدخل النص المرجعي، يعرض المقاييس على أنها غير متاحة ولا يخترع درجة جودة.
- يعرض النص المستخرج داخل الشاشة مؤقتاً ويسجل بيانات القياس الإجمالية في سجل العمليات؛ لا يحفظ الملف أو النص المرجعي أو التفريغ في سجل Benchmark.
- يظل اختبار الترجمة الموجود سابقاً في Benchmark Center كما هو.

## الخصوصية والتكلفة
- اختيار الملف وحده لا يرسل شيئاً. الإرسال يبدأ فقط بعد ضغط المستخدم زر تشغيل المقارنة.
- كل مزود مهيأ يستقبل الملف نفسه في طلب STT منفصل؛ قد تُحتسب تكلفة/حصة لكل مزود وفق حساب المستخدم.
- لا يتم تشغيل أي طلب API تلقائياً عند فتح الشاشة.
- المقارنة تستخدم واجهات التفريغ المسبق HTTP الموجودة في المشروع؛ ليست مقارنة جلسات WebSocket حية. Deepgram WebSocket ما زال مساراً منفصلاً في Live Capture.

## الملفات الجديدة
- `app/src/main/java/com/sari/subtitle/SttProviderBenchmarkEngine.kt`
- `app/src/main/java/com/sari/subtitle/SttProviderBenchmarkActivity.kt`
- `app/src/test/java/com/sari/subtitle/SttErrorRateEvaluatorTest.kt`

## الملفات المعدلة
- `app/src/main/AndroidManifest.xml` — تسجيل الشاشة الجديدة.
- `app/src/main/java/com/sari/subtitle/ProviderBenchmarkCenterActivity.kt` — إضافة رابط شاشة مقارنة STT.

## التحقق
- تحقق XML لملف AndroidManifest: ناجح.
- ترجمة Kotlin المستقلة لمقيّم WER/CER بواسطة kotlinc: ناجحة.
- أضيفت اختبارات JUnit للحالات الأساسية، لكن لم تُشغّل لعدم توفر Gradle/Gradle Wrapper في بيئة العمل.
- لم يتم بناء APK كامل، ولم تُرسل ملفات صوت إلى مزود خارجي، ولم يتم اختبار الشبكة أو واجهة Android على جهاز فعلي.

## ملاحظات حدود القياس
- WER/CER يعتمدان على نص مرجعي يدخله المستخدم؛ لا يمكن استنتاج دقة التفريغ من زمن الاستجابة وحده.
- زمن القياس يتأثر بالشبكة وحجم الملف وحالة الخادم؛ للحصول على مقارنة عادلة كرر القياس عدة مرات على نفس الملف والشبكة.
- الملفات المدعومة فعلياً تخضع لحدود كل مزود. خدمات STT الحالية في المشروع تفرض سقفاً يقارب 24MB.

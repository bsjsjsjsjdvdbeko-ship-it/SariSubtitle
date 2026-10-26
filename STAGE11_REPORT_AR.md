# تقرير Stage 11 — Live Subtitle Quality & Sync

## الهدف

معالجة مشكلتين أساسيتين في Stage 10:

1. شكل الترجمة فوق الفيديو لم يكن قريبًا من Subtitle احترافي.
2. نتائج الشبكة المتأخرة كانت تظهر بعد فوات وقتها، وقد تتراكم وتظهر بترتيب غير صحيح.

## التعديلات

### 1. Subtitle Overlay احترافي
- تم حذف شكل البطاقة والعنوان من Overlay.
- الترجمة أصبحت أسفل الشاشة، في المنتصف، بخط أبيض عريض مع Shadow.
- حد أقصى سطران.
- الخلفية شفافة.
- الـOverlay لا يستقبل اللمس حتى لا يمنع التحكم في الفيديو.

### 2. Timestamp/Cue Timing
- يتم استخدام `verbose_json` وSegment timestamps من Whisper.
- بداية الـcue تعتمد على وقت التقاط المقطع + بداية الـsegment.
- مدة العرض تعتمد على نهاية الـsegment بدل مدة ثابتة دائمًا.

### 3. منع تراكم التأخير
- Audio queue أصبحت بسعة عنصر واحد.
- إذا امتلأت، يتم إسقاط المقطع الأقدم والاحتفاظ بالأحدث.
- `LiveSubtitleBus` يرفض نتيجة قديمة عندما تكون نافذة صوت أحدث قد وصلت بالفعل.

### 4. تحسين Speech-to-Text
- Human Quality يستخدم `whisper-large-v3` لأنه الخيار الأعلى دقة في وثائق Groq الحالية.
- Fast/Balanced يستخدمان `whisper-large-v3-turbo` لتقليل التكلفة والزمن.
- تمت إضافة `prompt` يحمل المصطلحات والأسماء والسياق السابق.

### 5. تحسين نموذج الترجمة
- لم يتم استخدام `llama-3.3-70b-versatile` لأن وثائق Groq الحالية تعتبره Deprecated منذ 16 أغسطس 2026.
- Human Quality يستخدم `openai/gpt-oss-120b` في طلب واحد قوي بدل طلب ترجمة ثم طلب مراجعة ثانٍ؛ الهدف تقليل زمن الانتظار.
- Fast/Balanced يستخدمان `openai/gpt-oss-20b`.

## ملاحظة مهمة عن التزامن

إذا كان الفيديو في تطبيق خارجي مثل تطبيق فيديو آخر، لا يستطيع Sari Subtitle قراءة `currentPosition` لذلك التطبيق. لذلك لا يمكن ضمان تطابق زمني رياضي كامل بين subtitle وframe الفيديو. ما تم تحسينه هنا هو latency control، timestamping، منع stale results، وشكل الـsubtitle.

أما داخل مشغل Sari Subtitle نفسه، فيمكن لاحقًا ربط الـcue مباشرة بساعة Media3 للحصول على synchronization حقيقي مع `player.currentPosition`.

## التحقق

تم التحقق من:
- XML parsing لجميع ملفات الموارد.
- Pure Kotlin compilation للملفات التي لا تعتمد على Android SDK.
- عدم وجود `parcelableIntentExtra`.
- عدم وجود `webView.webViewClient = null`.
- عدم وجود `app:valueFrom/valueTo/stepSize`.
- `TranscriptDeduplicator` موجود مرة واحدة.

لم يتم تشغيل `assembleDebug` في بيئة التنفيذ الحالية لعدم توفر Android SDK/Gradle الكاملين.

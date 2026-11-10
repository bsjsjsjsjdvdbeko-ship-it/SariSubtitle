# Sari Subtitle — OmniRoute Performance & Reliability Phase 3

## المنفذ في هذه المرحلة

1. إضافة قياس فعلي لمدة معالجة STT لكل مقطع صوتي في `LiveCaptureService` باستخدام `SystemClock.elapsedRealtime()`.
2. تسجيل زمن المعالجة مقابل مدة الصوت الفعلية، وعدم اختلاق قيم أداء.
3. توسيع `AdaptiveLatencyController` ليتكيف مع نسبة زمن معالجة STT إلى مدة المقطع:
   - إذا كان STT أبطأ بوضوح من الصوت، يزيد طول المقطع تدريجياً لتقليل عدد الطلبات.
   - إذا كان STT سريعاً، يقلل طول المقطع تدريجياً لتحسين سرعة ظهور الترجمة.
   - الحدود 1200–2400ms.
4. استخدام امتلاء قائمة الانتظار كإشارة ضغط؛ وبعد تكرار الضغط يرفع طول المقطع ضمن الحدود.
5. نشر حالة `stt_performance` تتضمن زمن STT، مدة الصوت، حجم المقطع الموصى به، وعمق قائمة الانتظار.
6. إضافة إعادة محاولة محدودة (حتى 3 محاولات إجمالاً) لطلبات OmniRoute عند أخطاء الشبكة المؤقتة أو HTTP 408/429/5xx، مع احترام Retry-After عند توفره وحد أقصى 5 ثوانٍ لكل انتظار.
7. عدم إعادة محاولة أخطاء المصادقة/الطلبات غير القابلة للاسترداد، وعدم تغيير مزود بديل أو مفاتيح المستخدم.
8. إضافة اختبارات وحدة لمنطق التكيف والضغط.

## حدود المرحلة

- لا تحول `/audio/transcriptions` إلى WebSocket أو SSE؛ هذا endpoint يظل طلب HTTP لكل مقطع.
- لا يوجد ضمان لاستكمال طلب مقطع بعد انقطاع الاتصال؛ يعاد إرسال المقطع نفسه فقط ضمن حدود المحاولات.
- لا يمكن قياس التكلفة المالية الدقيقة من التطبيق دون بيانات فوترة موثوقة من المزود؛ لم تتم إضافة أرقام تكلفة تقديرية.
- لم يتم بناء APK في بيئة العمل لعدم وجود Gradle Wrapper في المشروع وعدم تأكيد توفر Gradle/Android SDK هنا.

## الملفات المعدلة

- `app/src/main/java/com/sari/subtitle/AdaptiveLatencyController.kt`
- `app/src/main/java/com/sari/subtitle/LiveCaptureService.kt`
- `app/src/main/java/com/sari/subtitle/OmniRouteWhisperService.kt`
- `app/src/test/java/com/sari/subtitle/AdaptiveSttPerformanceTest.kt` (جديد)

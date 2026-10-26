# OmniRoute Integration — Sari Subtitle

هذه المجلدات لا تحاول نسخ OmniRoute داخل APK. التطبيق Android هو **client**، بينما OmniRoute يعمل كـ gateway خارجي، وهذا هو التصميم الصحيح لأن gateway يحتاج Node/runtime واتصالات مزودي AI.

## المسار

`Sari Subtitle -> Sari AI Orchestrator -> OmniRoute -> Provider/Model`

OmniRoute يوفر واجهات OpenAI-compatible، ومنها `/v1/models` و`/v1/chat/completions` و`/v1/audio/transcriptions` حسب توثيق المشروع الحالي.

## تشغيل OmniRoute

استخدم إصدار OmniRoute الذي تعتمد عليه بيئتك، ثم شغله محلياً أو على جهاز/خادم يمكن للهاتف الوصول إليه عبر الشبكة. بعد التشغيل، اجعل Base URL في Sari يشير إلى عنوان الـ gateway، وليس `localhost` إذا كان gateway على جهاز آخر.

## إعداد Sari

- Base URL: عنوان OmniRoute مع أو بدون `/v1`.
- API Key: مفتاح الـ gateway.
- STT candidates: مثال `deepgram/nova-3,assemblyai/best`.
- Translation candidates: مثال `openai/gpt-oss-20b,openai/gpt-oss-120b`.
- Smart Routing: ترتيب المرشحين حسب الأداء الذي قاسه التطبيق فعلياً.
- Quality-aware Retry: الانتقال للمرشح التالي عند فشل الطلب أو فشل التحقق المحلي من النتيجة.

## ملاحظة مهمة

Sari لا يدّعي معرفة quota أو accuracy لمزود لا يعرضها فعلياً. الـ Sari Score مبني على latency وsuccess/failure وinvalid-response وcircuit-breaker، بينما quota/accuracy الحقيقية تبقى من مسؤولية OmniRoute/provider أو اختبار مرجعي حقيقي.

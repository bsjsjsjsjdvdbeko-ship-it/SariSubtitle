# Sari Subtitle Stage 12 — Full AI Orchestration

تم تنفيذ طبقة orchestration فعلية فوق OmniRoute داخل مشروع Stage 11/12.

## الميزات
- Intelligent Provider Scoring: نعم، من latency + success + observed quality + rate/quota.
- Accuracy selection: نعم كـ Observed Accuracy/Quality Proxy، وليس ground-truth accuracy غير المتاح بدون مرجع.
- Latency selection: نعم.
- Quality selection: نعم.
- Quota selection: نعم عند توفر quota حقيقي من headers/runtime؛ القيم غير المتاحة تبقى Unknown.
- Provider Health Score: نعم.
- Automatic Provider Benchmark: نعم، benchmark حقيقي قابل للتشغيل من Dashboard.
- Quality-Aware Retry: نعم.
- Compare two results: نعم في Balanced/Human عند تفعيل المقارنة؛ يتم اختيار أعلى جودة بعد validation مع latency penalty.
- Adaptive routing: نعم.
- Noise-aware routing: نعم عبر AudioFeatureAnalyzer.
- Speech-speed-aware routing: نعم عبر SpeechProfile (words/sec).
- Performance history: نعم عبر Room.
- Sari Provider Score: نعم.
- Decision Engine: نعم عبر RoutingDecision/CandidateScore.
- Dashboard: نعم.
- Zero-downtime intelligent failover: نعم على مستوى الطلب: circuit breaker + ordered fallback + rate-limit cooldown، دون إيقاف جلسة التطبيق.
- Streaming-aware orchestration: نعم للترجمة عبر SSE `stream=true` عند تفعيلها، مع fallback إلى المرشح التالي.
- Quota/Rate-Limit monitoring: نعم من HTTP headers، ومعالجة حقول OmniRoute runtime عندما تكون متاحة؛ لا يتم اختلاق quota.

## حدود الدقة
لا يوجد مصدر ground-truth داخل الترجمة الحية يثبت أن Provider A أدق لغويًا من Provider B. لذلك يسجل Sari Observed Quality/Accuracy Proxy من خصائص النتيجة ويستخدمه للتوجيه.

## البناء
لم يتم الادعاء بنجاح assembleDebug لأن بيئة التسليم لا تحتوي Android SDK/Gradle Wrapper كامل. تم إجراء فحص بنيوي وكتابي للملفات.

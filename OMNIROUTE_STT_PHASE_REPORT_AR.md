# المرحلة التالية: OmniRoute STT وربط الترجمة الحية

تمت إضافة عميل Whisper متوافق مع واجهة OpenAI multipart إلى عنوان OmniRoute، وربطه بمسار LiveSttRouter كخيار STT مستقل.

## الإعدادات
- مزود STT: `omniroute`
- مفتاح OmniRoute الحالي (المخزن الآمن)
- عنوان OmniRoute الحالي
- نموذج STT قابل للتغيير عبر `omniRouteSttModel`، الافتراضي `whisper-large-v3-turbo`.

## حدود المرحلة
- هذه ليست جلسة WebSocket/SSE streaming؛ إنها تفريغ مقاطع صوتية قصيرة عبر طلبات HTTP multipart متتابعة.
- لا بد أن يدعم مزود OmniRoute endpoint `/v1/audio/transcriptions` والنموذج المختار.
- لم يتم تغيير MediaProjection/AudioRecord أو دورة التقاط الصوت.
- يجب تشغيل البناء والاختبار على مزود حقيقي قبل اعتمادها.

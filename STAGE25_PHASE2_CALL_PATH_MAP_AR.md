# المرحلة الثانية — خريطة الاستدعاءات ومسارات البيانات

## نطاق المرحلة

هذه المرحلة تتعقب البنية الحالية لنسخة `SariSubtitle_Stage25_Audit_Phase1_GitHubBuildFix.zip` دون تعديل ملفات المصدر. الغرض هو تحديد مسارات التنفيذ ونقاط عبور البيانات والمخاطر التي تحتاج اختبارًا أو إصلاحًا لاحقًا. هذه خريطة ساكنة مبنية على قراءة الكود؛ لا تدّعي أنها تغطي كل سلوك وقت التشغيل ولا تستبدل Logcat أو اختبار Android.

## 1. نقطة الدخول والبنية

- `app/src/main/AndroidManifest.xml`: يعرّف `SariSubtitleActivity` كنقطة الدخول `MAIN/LAUNCHER`، و`LocalMediaPlayerActivity` و`SubWhisperInternalActivity` كأنشطة داخلية.
- `SariSubtitleActivity.onCreate()` (`SariSubtitleActivity.kt`): يحمّل `activity_main.xml`، وينشئ `SubtitleSettings` و`SrtStorage` و`SubtitleRepository`، ويهيّئ TTS وWebView وأزرار الواجهة، ثم يبدأ `startPolling()`.
- `WebViewClient.onPageFinished()` (`SariSubtitleActivity.kt:135-150`): يحقن سلوك العرض التلقائي، ويستدعي `tryAutoLoadSrt(url)`، وقد يبدأ التفريغ التلقائي المؤجل إذا تطابق الرابط المطلوب.
- `SariSubtitleActivity.onDestroy()` (`SariSubtitleActivity.kt` قرب النهاية): يلغي العملية المتتبعة، ويغلق TTS وCallbacks وWebView.

## 2. مسار «ملفاتي» — فتح عنصر

```text
activity_main.xml / savedItemsButton
  -> SariSubtitleActivity: showSavedSubtitles()
      -> SrtStorage.listLibraryItems()  [library-index.json + ملفات library_*.srt]
      -> SrtStorage.listUrls()          [index.json + روابط الفيديو]
      -> بناء rows و ListView
          -> عند النقر على عنصر مكتبة:
              -> loadLibraryItem(id)
              -> SrtParser.parse(SRT)
              -> SubtitleRepository.setCues(cues)
              -> إذا كان sourceUrl من نوع HTTP(S):
                    -> storage.save(sourceUrl, srt, cueCount) [نسخة cache]
                    -> urlInput.setText(sourceUrl)
                    -> webView.loadUrl(sourceUrl)
              -> إذا كان content:// أو file://:
                    -> LocalMediaPlayerActivity مع SRT عبر Intent extras
          -> عند النقر على صف رابط فقط:
              -> webView.loadUrl(url)
              -> restoreCachedSrt(url)
          -> عند الضغط المطول:
              -> deleteLibraryItem(id) أو delete(url)
              -> إعادة بناء القائمة
```

### حدود مثبتة من الكود

- بناء القائمة يعتمد على فهرسين مستقلين: `library-index.json` و`index.json`. لذلك قد يظهر ملف SRT كعنصر مكتبة دون أن يكون له cache مرتبط بالرابط، أو يظهر رابط دون عنصر مكتبة.
- `listLibraryItems()` و`listUrls()` يلتقطان الاستثناءات ويعيدان قائمة فارغة. عند تلف الفهرس قد تظهر رسالة «لا توجد ملفات» بدل رسالة تشخيصية؛ هذا لا يعني أن ملفات SRT نفسها اختفت.
- فتح عنصر المكتبة ذي المصدر HTTP(S) يستدعي `storage.save()` لكنه لا يفحص نتيجته قبل متابعة فتح الفيديو. إذا فشل إنشاء نسخة cache، قد تظل نسخة المكتبة قابلة للفتح، لكن الاستعادة التلقائية عبر فهرس URL لن تكون مضمونة.
- مسار انهيار «ملفاتي» لا يمكن إثباته من القراءة الساكنة وحدها؛ يلزم Stack Trace من الجهاز. نقاط المراقبة الأولوية: معالج النقر، `loadLibraryItem`, `SrtParser.parse`, `startActivity`, وعمليات WebView.

## 3. مسار حفظ SRT المرتبط بالفيديو واستعادته

```text
مصدر SRT / تفريغ / ترجمة
  -> نص SRT
  -> SrtParser.parse() للتحقق من المقاطع
  -> SrtStorage.save(url, text, cueCount)
      -> normalizeUrl(url) / canonicalKey(url)
      -> readIndex(index.json)
      -> كتابة ملف SRT بإصدار مستقل
      -> تحديث index.json عبر ملف مؤقت
      -> بعد نجاح الفهرس، حذف النسخة السابقة

فتح الفيديو مجددًا
  -> WebViewClient.onPageFinished(url)
  -> tryAutoLoadSrt(url)
  -> restoreCachedSrt(url)
  -> SrtStorage.load(url)
  -> findEntryPair(): hash مباشر ثم canonical key ثم normalized/legacy URL
  -> قراءة SRT -> SrtParser.parse -> repo.setCues
  -> تعيين lastSrtText / lastSrtUrl / overlayOn
  -> startPolling يحدد cue حسب وقت الفيديو ويعرض SubtitleOverlay
```

### نقاط تحقق أساسية

- `SrtStorage.save()` يكتب الملف أولًا ثم الفهرس، ويستخدم اسم ملف جديدًا لكل نسخة لتقليل احتمال فقدان النسخة القديمة إذا فشل تحديث الفهرس.
- `SrtStorage.load()` يعيد `null` عند غياب سجل أو ملف أو عند الاستثناء؛ لا يميز حاليًا بين «لا توجد ترجمة» و«الفهرس تالف» للواجهة.
- `tryAutoLoadSrt()` يعتمد على `onPageFinished`. بعض مواقع الفيديو تستخدم تنقل SPA عبر `history.pushState` دون تحميل صفحة كامل؛ لذلك قد لا يحدث استدعاء `onPageFinished` لكل انتقال داخلي. يجب اختبار تنقل X/YouTube داخل WebView، وليس فقط إغلاق التطبيق وفتحه.
- `VideoUrlIdentity.normalize()` يبني المفتاح العام `url:host/path?...` دون إدراج scheme في القيمة النهائية؛ لذلك قد يتطابق HTTP وHTTPS لنفس host/path. يلزم قرار صريح واختبار لتفادي استعادة ترجمة عبر scheme مختلف إذا لم يكن ذلك مقصودًا.
- `canonicalKey` لـ X/YouTube يعتمد على معرّف المنشور/الفيديو؛ هذه قاعدة مفيدة لإزالة معاملات التتبع، ويجب اختبارها على روابط مختلفة فعليًا قبل تعديلها.

## 4. مسارات إنشاء/استيراد/ترجمة SRT

| نقطة البداية | مسار المصدر | الحفظ المرصود في الكود | ملاحظة التتبع |
|---|---|---|---|
| زر `loadSrtButton` | `openSrtPicker()` → `processSmartSrt(uri)` | حسب حالة النص، يتم ربط cache بالرابط الحالي وحفظ عنصر مكتبة في المسار الذكي | قراءة الملف تستخدم `readBytes()` دون حد حجم موحد |
| SRT من رابط مباشر | `downloadSrtFromUrl()` | `storage.save(videoUrl, text, cues.size)` | حد 5 MiB موجود في مسار التنزيل المباشر |
| ترجمة SRT تلقائية | `translateAndShowSrtAutomatically()` | `storage.save()` + `saveLibraryItem()` | تعرض حالة الحفظ للمستخدم |
| SRT مولّد/مترجم في المسار الذكي | مسار المعالجة الرئيسي داخل `SariSubtitleActivity` | بعض الفروع تحفظ cache ومكتبة، وفروع احتياطية تحفظ cache فقط | يجب اختبار كل فرع؛ لا تُعمم نتيجة فرع على جميع المزودات |
| تفريغ صوت/فيديو | `transcribeWithSelectedProvider()` ثم معالجة النتيجة | عدة فروع تحفظ cache، وبعضها يحفظ المكتبة أيضًا | ربط المصدر يعتمد على `currentVideoUrlForCache()` أو URL احتياطي |
| ترجمة ملف مستقل من زر «ترجمة SRT» | `translateSelectedSrt()` → `saveTranslatedSrt(uri)` | يصدر الملف إلى URI اختاره المستخدم | هذا المسار لا يحفظ الناتج تلقائيًا في `SrtStorage` ولا يربطه بفيديو؛ قد يكون مقصودًا لملف مستقل لكنه ليس مسار استعادة الفيديو |
| SRT من SubWhisper | `onActivityResult(REQ_SUBWHISPER_INTERNAL)` | يحفظ cache إذا توفر رابط فيديو حالي | fallback يقرأ مفتاحًا ثابتًا `subwhisper-latest` من cache URL-index؛ يلزم اختبار توافقه مع صيغة المفتاح |

## 5. إدارة العمليات والشبكة

- `beginTrackedOperation()` يلغي العملية السابقة، ثم يستدعي `NetworkRequestRegistry.beginOperation()`.
- `OperationCancellation.cancel()` يستدعي `NetworkRequestRegistry.cancelAll()` ويقاطع Thread العامل.
- أغلب عملاء HTTP يسجلون الاتصالات في مجموعة عالمية `NetworkRequestRegistry` ثم يلغون تسجيلها في `finally`.

**مخاطرة مثبتة في التصميم:** `cancelAll()` عالمي وليس مربوطًا بمعرّف العملية؛ إلغاء عملية واحدة قد يفصل اتصال HTTP يخص عملية أخرى متزامنة (مثل اختبار مزود أو مهمة بدأت من شاشة أخرى). لا نغيّر ذلك في مرحلة رسم المسارات؛ الإصلاح اللاحق ينبغي أن يربط كل اتصال بكائن/معرّف العملية ويضيف اختبار تزامن.

## 6. خريطة المكونات حسب المسؤولية

### واجهة التطبيق والتشغيل
- `SariSubtitleActivity.kt` — نقطة التجميع الرئيسية: WebView، الأزرار، اختيار الملفات، الترجمة والتفريغ والحفظ والتشغيل.
- `LocalMediaPlayerActivity.kt` — تشغيل فيديو محلي مع SRT يمر عبر Intent؛ ينسخ `content://` إلى cache عند تعذر تشغيل المصدر مباشرة.
- `SubWhisperInternalActivity.kt` — WebView داخلي والتقاط SRT من الموقع المسموح؛ يعتمد على جسر JavaScript مع حالة ثقة.
- `SettingsDialog.kt` — إعدادات العرض والنموذج المحلي.
- `TranslationProvidersDialog.kt` — إعدادات مزودات الترجمة واختبار OmniRoute.
- `SubtitleOverlay.kt` — عرض النص فوق الفيديو.

### التخزين والتحليل
- `SrtStorage.kt` — `index.json` و`library-index.json` وملفات SRT.
- `SrtParser.kt` — فك ترميز UTF-8/UTF-16/Windows-1256 وتحليل التوقيت والمقاطع.
- `SubtitleRepository.kt` — القائمة الحالية من المقاطع والبحث حسب زمن الفيديو والإزاحة.
- `SubtitleCue.kt` — نموذج المقطع الزمني.
- `VideoUrlIdentity.kt` — تطبيع الروابط والمفتاح canonical.
- `SubtitleSettings.kt` — إعدادات العرض والمزودات وتفضيلات التطبيق.

### الترجمة واكتشاف اللغة
- `SrtTranslationService.kt` — اكتشاف لغة المقاطع، تجميعها حسب اللغة، الترجمة، ومحاولة استعادة المقاطع المفقودة بالتوقيت.
- `LanguageDetector.kt` — طلب Google Translate للكشف عن اللغة.
- `GeminiTranslationService.kt` — ترجمة دفعات نصية عبر Gemini.
- `OmniRouteTranslationClient.kt` — ترجمة دفعات عبر واجهة OpenAI-compatible محلية/بعيدة.
- `DeepLTranslationService.kt` — DeepL ثم Google/MyMemory كبدائل.
- `AdditionalTranslationProviders.kt` — Azure وHugging Face.

### التفريغ الصوتي والنماذج
- `GroqWhisperService.kt` — رفع/تفريغ عبر Groq.
- `OpenAIWhisperService.kt` — تفريغ عبر OpenAI.
- `DeepgramTranscriptionService.kt` — تفريغ عبر Deepgram.
- `OfflineVoskTranscriber.kt` — تفريغ محلي من PCM.
- `VoskModelManager.kt` — تنزيل النموذج والتحقق من الأرشيف واستخراجه.
- `YouTubeCaptionExtractor.kt` — استخراج captions عامة من YouTube.
- `LinkSupportAnalyzer.kt` — تحليل دعم الرابط.

### المفاتيح والإخراج
- `GroqApiKeyStore.kt`, `DeepgramApiKeyStore.kt`, `OpenAIApiKeyStore.kt`, `GeminiApiKeyStore.kt`, `DeepLApiKeyStore.kt`, `OmniRouteApiKeyStore.kt`, `ExtraApiKeyStore.kt` — مخازن مفاتيح منفصلة تعتمد على Android Keystore.
- `WavDubbingExporter.kt` — تصدير WAV من TTS ومزج المقاطع.
- `OperationCancellation.kt` — إلغاء العمليات والاتصالات.

## 7. نتائج الفحص الساكن في المرحلة الثانية

1. **تغطية الاختبارات محدودة:** ملف اختبار JVM ظاهر واحد (`SrtParserTest.kt`)؛ لا توجد اختبارات `androidTest` في الأرشيف. لذلك معظم تدفقات Activity/WebView/Storage لا تزال تحتاج اختبارات تكامل/جهاز.
2. **قراءة ملفات بلا سقف موحد:** `processSmartSrt`, `processTranslateSrt`, `translateSelectedSrt`, `loadSrtFromUri` و`SrtParser.parse(InputStream)` تستخدم `readBytes()`؛ يمكن أن يستهلك ملف ضخم الذاكرة قبل أن يُرفض.
3. **كشف اللغة لكل cue:** `SrtTranslationService.translateAuto()` يستدعي `LanguageDetector.detect()` داخل حلقة المقاطع غير المصنفة محليًا؛ قد ينتج عددًا كبيرًا من طلبات الشبكة ووقت انتظار مرتفعًا.
4. **فهرس تالف يعرض مكتبة فارغة:** `listLibraryItems()` و`listUrls()` يعيدان قوائم فارغة عند فشل قراءة JSON، دون مسار إصلاح/نسخ احتياطي للمستخدم.
5. **حذف غير متسق عند فشل حذف الملف:** `deleteLibraryItem()` و`delete()` يزيلان سجل الفهرس أولًا، ثم يحاولان حذف الملف. إذا فشل حذف الملف، قد تعود `false` بعد إزالة العنصر من الفهرس وترك ملف يتيم.
6. **إلغاء الشبكة عالمي:** إلغاء عملية واحدة يفصل جميع الاتصالات المسجلة، وليس فقط اتصالات تلك العملية.
7. **سياسة HTTP واسعة:** `usesCleartextTraffic=true` على مستوى التطبيق؛ يوجد سبب توافق محتمل لـ OmniRoute على localhost، لكن السماح ليس مقيدًا بذلك المضيف.
8. **أداء المشغل المحلي:** `LocalMediaPlayerActivity.startSubtitleTicker()` يبحث بـ`firstOrNull` على قائمة cues كل 120ms؛ كلفة البحث خطية مع عدد المقاطع. يلزم قياس على ملف طويل قبل تغيير الخوارزمية.
9. **مسار الترجمة المنفصلة لا يحفظ cache تلقائيًا:** `translateSelectedSrt()` ثم `saveTranslatedSrt()` يكتب إلى URI يختاره المستخدم ولا يحفظ تلقائيًا في المكتبة. يجب تأكيد هل هذا السلوك مقصود للملفات غير المرتبطة بفيديو.
10. **مطابقة scheme:** `VideoUrlIdentity.normalize()` لا يدخل scheme في مفتاح URL العام؛ يلزم اختبار ما إذا كان تطابق HTTP/HTTPS مقصودًا.

## 8. مصفوفة التحقق المطلوبة لاحقًا

| الاختبار | الإجراء | معيار النجاح |
|---|---|---|
| Library-SRT | فتح كل عنصر مكتبة صالح وفارغ/تالف | لا انهيار؛ رسالة مفهومة للعنصر غير الصالح |
| Library-URL | فتح كل صف رابط فقط | فتح الصفحة واستعادة cache المطابق إن وجد |
| Delete | حذف ملف مكتبة ورابط cache ثم إعادة فتح القائمة والتطبيق | الحالة المعروضة تطابق الملفات والفهارس الفعلية |
| Persist | ترجمة/حفظ SRT ثم إغلاق التطبيق بالقوة وإعادة فتح الرابط نفسه | استعادة المقاطع دون إعادة الترجمة |
| Different video | فتح فيديو مختلف بعد وجود ترجمة سابقة | لا تظهر ترجمة الفيديو السابق |
| SPA navigation | الانتقال بين فيديوهات ضمن WebView دون إعادة تحميل كامل | اكتشاف تغير الفيديو/الرابط وإعادة التحقق من cache |
| Corrupt index | اختبار نسخة بيانات تجريبية بفهرس JSON تالف | لا تُعرض المكتبة فارغة بلا تشخيص ولا تُستبدل البيانات القديمة |
| Concurrent cancel | إلغاء ترجمة بينما اختبار اتصال آخر يعمل | تُلغى اتصالات العملية المقصودة فقط |
| Large SRT | استيراد ملفات بأحجام متزايدة | لا انهيار ذاكرة؛ رفض مبكر برسالة واضحة عند تجاوز الحد |
| Providers | اختبار فشل/مهلة كل مزود | رسائل خطأ واضحة وعدم حفظ نتيجة جزئية كنجاح كامل |

## الحالة

اكتملت خريطة المسارات الأساسية وجرد مسؤوليات ملفات المصدر في هذه المرحلة. لم تتغير ملفات Kotlin/XML أو إعدادات البناء في المرحلة الثانية؛ التغييرات هنا توثيقية فقط. لا يزال سبب انهيار «ملفاتي» غير مثبت دون Logcat/اختبار جهاز، ولم يُنفذ Gradle أو تثبيت APK محليًا؛ البناء المطلوب يبقى عبر GitHub Actions فقط.

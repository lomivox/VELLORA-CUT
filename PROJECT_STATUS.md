# VELLORA CUT — Project Status

اس فائل کو ہر بڑی تبدیلی کے بعد اپڈیٹ کریں۔ مقصد: کوئی بھی chat (نئی ہو یا پرانی) صرف یہ فائل پڑھ کر فوراً موجودہ حالت سمجھ سکے، پورا روڈ میپ دوبارہ پڑھے بغیر۔

---

## بنیادی فیصلے (طے شدہ، تبدیل نہ کریں بغیر بحث کے)

- **Stack:** Kotlin + Jetpack Compose (UI) → Room DB (project/prompt storage) → RenderEngine (video export)
- **Package:** `com.vellora.cut`
- **Repo:** `github.com/lomivox/VELLORA-CUT`
- **App icon:** VELLORA CUT برانڈڈ (V + cut/play + cyan glow line)، adaptive icon مکمل تمام densities میں موجود
- **موجودہ آرکیٹیکچر:** پرانا manual clip-editor (EditorScreen/HomeScreen/TimelineView/AiUhdSheet/NativeEngine/C++/NDK/FFmpeg pipeline) مکمل طور پر **حذف** کر دیا گیا ہے۔ اب پوری ایپ ایک واحد **"Auto Generator"** فلو پر بنی ہے: پرامپٹس → AI امیجز → Timeline → Render → mp4 (`app/src/main/java/com/vellora/cut/autogen/` module)
- **Reference/blueprint:** پرانی CapCut Mini (Python/Flask/WebView پروٹوٹائپ) `reference/CapCutMini_project.zip` میں محفوظ ہے — صرف حوالے کے لیے (icon/layout proportions کے لیے استعمال ہوئی)

## ورک فلو (Termux + Chat کے درمیان)

- بڑی تبدیلیاں (نئی فائلیں/متعدد فائلیں) → Claude فائل بنا کر deliver کرتا ہے → Termux میں `/storage/emulated/0/Download/` سے صحیح جگہ `cp` → `git add` → `git commit` → `git push`
- ہمیشہ commit+push کر کے ہی نئی chat یا نیا کام شروع کریں؛ نئی chat شروع کرنے سے پہلے `git pull` ضرور کریں
- کمزور نیٹ ورک پر `git clone` fail ہو تو `git clone --depth 1 ...` استعمال کریں (پوری history کے بغیر، تیز)

---

## موجودہ اسکرین: TimelineScreen (5-section proportional layout)

`autogen/ui/TimelineScreen.kt` اب مرکزی ایڈیٹر اسکرین ہے، اور دی گئی proportions کے مطابق 5 حصوں میں بٹی ہے:

| سیکشن | تناسب | حالت |
|---|---|---|
| **Top Bar** | 6.8% | ✅ Close button فعال، **Export** button فعال (cyan pill، render trigger کرتا ہے) |
| **Preview** | 48.7% | ✅ فعال — اصل generated images دکھاتا ہے، audio کے بغیر بھی اپنے wall-clock timer سے چلتا ہے، audio ہو تو اس سے sync ہو جاتا ہے |
| **Controls** | 4.9% | Play/Pause ✅ فعال (hand-drawn glyph، کوئی رنگ کا مسئلہ نہیں)؛ Fullscreen، Snap toggle، Undo، Redo ⬜ صرف نمائشی |
| **Timeline** | 30% | ✅ مکمل فعال — Summary card، Sync Mode، Transition، Motion Effect chips، Images list، **Render → mp4** (اصل RenderEngine سے جڑا) — یہ حصہ کبھی نہیں چھیڑا گیا |
| **Navigation** | 9.8% | Audio ✅ فعال (system picker، project میں voice-over save کرتا ہے)، Ratio ✅ فعال (youtube/tiktok toggle)؛ Split, Text, Volume, Noise, Speed, Filter, Rotate, Overlay, Background ⬜ صرف نمائشی |

تمام reusable UI پیس (`EditorTopBarReference`, `PreviewMiddleControlsReference`, `BottomToolbarReference`) `autogen/ui/reference/EditorControlsReference.kt` میں ہیں — سائز/style ایک reference CapCut screenshot سے match کیا گیا ہے۔

**بٹن کا اسکور: 6 / 15 فعال** (Close, Export, Play/Pause, Audio, Ratio + implicit Timeline buttons)۔ باقی 9 (Fullscreen, Snap, Undo, Redo, Split, Text, Volume, Noise, Speed, Filter, Rotate, Overlay, Background) کے لیے پہلے فیچر ڈیزائن decide کرنا ہے (جیسے Split کا Timeline کی images پر کیا مطلب ہوگا)۔

---

## آخری بڑی تبدیلیاں (تاریخ کے لحاظ سے، نیچے سے اوپر پڑھیں)

- پرانا manual editor (EditorScreen, HomeScreen, TimelineView, AiUhdSheet, NativeEngine, C++/cpp folder) مکمل حذف — autogen-only architecture
- `EditorControlsReference.kt` میں unclosed-KDoc-comment bug فکس (`timeline/*` نے nested comment کھول دیا تھا، build fail ہو رہا تھا)
- `TimelineScreen.kt` کو 5-section proportional layout میں rebuild کیا (commit `bd52004`)
- 4 بٹن وائر کیے: Export, Play/Pause, Audio-upload, Ratio-toggle (commit `bd52004`)
- Top/Controls/Navigation کے icons ایک reference screenshot سے سائز match کیے؛ Export کو filled cyan pill بنایا؛ Preview کے اندر کا duplicate Play/Pause+time-counter ہٹایا (commit `b426290`)
- Pause icon کا yellow-tint bug فکس — emoji (▶/⏸) کی جگہ hand-drawn Canvas glyph استعمال کیا (چونکہ کچھ ڈیوائسز پر emoji کا اپنا fixed رنگ ہوتا ہے، tint نظرانداز کرتا ہے)
- Preview کا Play/Pause پہلے صرف audio موجود ہونے پر کام کرتا تھا (position صرف MediaPlayer سے آتی تھی)؛ اب اپنے wall-clock timer سے چلتا ہے، audio ہو تو اس سے sync

---

## معلوم مسائل / اگلے قدم

- 9 Controls/Navigation بٹن ابھی صرف نمائشی ہیں — ہر ایک کے لیے پہلے یہ طے کرنا ہے کہ وہ Timeline کے موجودہ data-model (images + audio، کوئی الگ سے clip-editing engine نہیں) کے ساتھ عملاً کیا کرے گا
- Undo/Redo کے لیے کوئی state-history ابھی موجود نہیں
- Fullscreen preview ابھی صرف icon ہے، حقیقی fullscreen mode نہیں بنایا گیا

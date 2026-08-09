# VELLORA CUT — Project Status

اس فائل کو ہر بڑی تبدیلی کے بعد اپڈیٹ کریں۔ مقصد: کوئی بھی chat (نئی ہو یا پرانی) صرف یہ فائل پڑھ کر فوراً موجودہ حالت سمجھ سکے، پورا روڈ میپ دوبارہ پڑھے بغیر۔

---

## بنیادی فیصلے (طے شدہ، تبدیل نہ کریں بغیر بحث کے)

- **Stack:** Kotlin + Jetpack Compose (UI) → JNI → C++/NDK (engine) → FFmpeg (media) → OpenGL/Vulkan (GPU، Phase 3 میں) → ONNX Runtime (AI، Phase 5 میں)
- **Package:** `com.vellora.cut`
- **NDK version:** 27.2.12479018 (stable, rc نہیں)
- **FFmpeg:** license-conscious — source سے خود NDK کے ذریعے compile ہوگا، کوئی mobile-ffmpeg/GPL prebuilt نہیں
- **Native build صرف CI پر:** `app/build.gradle.kts` میں `if (System.getenv("CI") != null)` — مقامی Termux build کبھی NDK نہیں مانگتی، صرف GitHub Actions پر compile ہوتی ہے (فون کی memory/connection بچانے کے لیے یہ فیصلہ ہوا تھا)
- **Repo:** `github.com/lomivox/VELLORA-CUT` (پہلے نام "capcut" تھا، rename ہوا)
- **Reference/blueprint:** پرانی CapCut Mini (Python/Flask/WebView پروٹوٹائپ) `reference/CapCutMini_project.zip` میں محفوظ ہے — صرف حوالے کے لیے، کبھی چلے گی نہیں، کوڈ سیدھا کاپی نہیں ہوتا بلکہ logic reference کے طور پر دیکھا جاتا ہے
- **App icon:** VELLORA CUT برانڈڈ (V + cut/play + cyan glow line)، adaptive icon مکمل تمام densities میں موجود

## ورک فلو (Termux + Chat کے درمیان)

- بڑی تبدیلیاں (نئی فائلیں/متعدد فائلیں) → Claude فائل بنا کر zip دیتا ہے (ہر zip کا نام منفرد) → Termux میں unzip → صحیح جگہ کاپی → commit → push
- چھوٹی تبدیلیاں (1-2 لائن) → سیدھا Termux `sed`/inline command
- ہمیشہ commit+push کر کے ہی نئی chat یا نیا کام شروع کریں؛ نئی chat شروع کرنے سے پہلے `git pull` ضرور کریں

---

## Phase کی حالت

### ✅ Phase 0 — Foundation (مکمل)
- Kotlin/Compose scaffold بن چکا، پہلی build کامیاب (مقامی Termux پر)
- GitHub Actions CI بن چکا (`.github/workflows/android-ci.yml`) — checkout → JDK 17 → Android SDK → NDK+CMake install → `./gradlew assembleDebug` → unit tests → APK upload
- **تصدیق شدہ:** CI پر C++/CMake واقعی compile ہوا (`configureCMakeDebug[arm64-v8a]`, `buildCMakeDebug[arm64-v8a]` وغیرہ log میں نظر آئے) — یعنی Kotlin → JNI → C++ pipeline کا بنیادی ہدف پورا ہوا
- App icon شامل اور manifest میں reference ہو چکا
- Native engine ابھی صرف ایک "ping" stub ہے (`native-lib.cpp`) — حقیقی FFmpeg logic ابھی نہیں، یہ متوقع ہے (Phase 2 کا کام)

### 🟡 Phase 1 — Timeline + Basic Editing (تقریباً 30-35%، جاری)
موجود:
- `HomeScreen.kt` — video picker، entry screen ✅ فعال
- `EditorScreen.kt` — Media3/ExoPlayer preview کے ساتھ CapCut طرز کا layout؛ اب `TimelineView` composable کو صحیح طریقے سے استعمال کرتا ہے (پہلے duplicate/غیر مربوط تھا، ابھی ٹھیک ہوا)؛ play/pause اور scrub حقیقی طور پر ExoPlayer سے جڑے ہیں
- `TimelineView.kt` — ruler، horizontal scroll، center-line، drag-to-seek سب فعال؛ ابھی صرف ایک ہی ویڈیو کلپ دکھاتا ہے (multi-clip، split، trim ابھی باقی)
- `AiUhdSheet.kt` — Export settings کا مکمل UI (resolution/fps/bitrate sliders) — صرف UI ہے، حقیقی export logic (FFmpeg سے جوڑنا) ابھی Phase 2 میں ہوگا
- Bottom toolbar کے تمام بٹن (Trim, Text, Audio, Filter, Rotate...) ابھی صرف ظاہری ہیں، کوئی فعالیت نہیں

باقی (Phase 1 مکمل کرنے کے لیے):
- Multi-clip timeline (ایک سے زیادہ clips شامل کرنا)
- Split / Delete / Duplicate / Trim (حقیقی فعالیت)
- Undo/Redo stack
- Room DB سے project save/load کو حقیقتاً جوڑنا (ابھی schema موجود ہے، استعمال نہیں ہو رہا)

### ⬜ Phase 2 — Native Export Engine (شروع نہیں ہوا)
### ⬜ Phase 3 — GPU Layer (شروع نہیں ہوا)
### ⬜ Phase 4 — Save/Load + Waveform + Audio (شروع نہیں ہوا)
### ⬜ Phase 5 — AI Features (شروع نہیں ہوا؛ Phase 5d پر فیصلہ ابھی باقی ہے — روڈ میپ سے نکالنا ہے یا "future/optional" رکھنا ہے)

---

## آخری بڑی تبدیلیاں (تاریخ کے لحاظ سے، نیچے سے اوپر پڑھیں)

- Phase 0: gradle wrapper + CI-only native C++/NDK build via GitHub Actions
- VELLORA CUT adaptive app icon (تمام densities + Play Store)
- AndroidManifest میں icon reference شامل
- (دوسری chat سے) HomeScreen, EditorScreen, TimelineView, AiUhdSheet, Theme شامل ہوئے
- Fix: TimelineView.kt کو EditorScreen.kt میں صحیح طریقے سے wire کیا (پہلے duplicate inline UI تھی)
- Fix: AiUhdSheet.kt اور TimelineView.kt میں رہ گیا "CapCut" نام ہٹا کر "VELLORA CUT" / neutral کیا گیا

---

## معلوم مسائل / نوٹس

- Bottom toolbar کے icons (✂ Trim, T Text, وغیرہ) صرف نمائشی ہیں — دبانے پر کچھ نہیں ہوتا
- `AiUhdSheet` کے تمام sliders UI میں کام کرتے ہیں مگر export logic سے جڑے نہیں
- Room DB (`AppDatabase.kt`, `ProjectEntities.kt`) بن چکا مگر ابھی کہیں استعمال نہیں ہو رہا (کوئی DAO/repository نہیں لکھا گیا)

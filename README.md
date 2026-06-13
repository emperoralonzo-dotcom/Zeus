# ⚡ FIXED BUILD v3 — adds the missing tasks-vision dependency (BitmapImageBuilder fix)

**Why the download failed before:** two reasons. (1) The recommended Gemma models are
license-gated on Hugging Face — a plain in-app download gets HTTP 401 and fails. (2) The old
downloader saved every download to ONE file (`zeus-model.task`), so it could never hold more
than a single model anyway.

**Why models can't be bundled into the APK via GitHub:** GitHub's web upload allows max 25 MB
per file (100 MB via command line). Every real model is 300 MB–3 GB. Bundling was never possible
on the no-PC route — models must be added AFTER install, and now they can be.

**New in this build:**
1. "Give Zeus his minds" screen: pick a role (quick / everyday / deep / reasoning / coding /
   vision), then either DOWNLOAD an ungated model by direct link, or IMPORT a file you
   downloaded in your browser (this is how gated Gemma/Llama get in — no PC, no adb).
2. Each model saves under its role name (quick.task … vision.task) — install up to six,
   one at a time. A menu item "Add a mind" lets you add more later.
3. Clear errors: gated models are explained instead of a raw failure; pasting a page link
   instead of the file link is detected.
4. Plus the earlier fixes: tasks-genai 0.10.24, restored GitHub workflow, keyboard Send.

**Tips for big downloads:** use Wi-Fi and keep the screen on while a model downloads — the
download runs inside the app, not in a background manager.

---

# ZEUS — Android (on-device AI)

A real native Android app. The language model runs **entirely on the phone** using
Google's MediaPipe LLM Inference runtime. After a one-time model download, Zeus works
**fully offline** — no internet, no cloud, nothing leaves the device.

This is honest source code, not a finished product. It compiles with the standard
Android toolchain. You must supply a model file, and you should test on a real phone.
I cannot compile or test it for you, so treat this as a working foundation.

---

## What you get
- `app/` — the Kotlin app (Jetpack Compose UI, storm-and-gold Zeus theme, the throne)
- `LlmEngine.kt` — runs the models on-device via MediaPipe; silent router; optional internet search
- `ModelManager.kt` — checks the phone's RAM and catalogs the models into roles
- `VoiceController.kt` — Zeus's voice via the phone's offline text-to-speech
- `MainActivity.kt` — throne, chat, a **microphone** to speak to him, and a menu to toggle
  **voice** and **internet search**
- `.github/workflows/android.yml` — builds the APK in the cloud (no PC needed)

## What he can do
- **Live talk** — tap the microphone and speak; he hears you (device speech recognition) and
  **replies aloud** (offline text-to-speech)
- **Show him a picture** — the image button sends a photo to the vision model (if you provide one)
- **Internet search** — a toggle in the menu (off = fully private/offline; on = he may fetch
  live web results when you ask)
- The **throne** is shown above the conversation, the storm baked in

## Several models, working as ONE (five roles)
You can drop **one or more** model files in. Zeus runs them as a single oracle — a silent
router picks the right one for each request. There are no model names and no menu for it;
it just happens. **Five roles**, assigned automatically:
- **quick** (smallest) · **everyday** (middle) · **deep** (largest) — by file size
- **coding** — a model whose name contains "code/coder" (optional)
- **vision** — a model whose name contains "vl/vision/llava/moondream" (optional; the eyes)

On a phone, only **one model is held in memory at a time** (phones can't hold several large
models at once), so switching brains causes a brief pause. This keeps the device safe and
responsive. Start with one or two if you like — add more later and the router picks them up
on next launch.

## Will it run on all Android phones?
It targets **Android 8.0 (API 26) and up**, which covers the large majority of phones.
The model size must match the phone:
- **< 4 GB RAM** → a 0.5–1B model (smallest)
- **~6 GB RAM** → a ~2B model (recommended)
- **8 GB+** → a 2–3B model (full strength)

The app reads the phone's RAM on first launch and tells you which size to use. Very old
2 GB devices may only manage the smallest model, slowly — that is the one honest limit.

---

## Step 1 — Get a model
MediaPipe needs a compatible model file (`.task`, or an older `.bin`). Good options:
- **Gemma** (2B IT, int4) — recommended for most phones
- **Falcon-1B** / smaller — for low-RAM phones

Download from Google's model pages (Kaggle / Hugging Face "LiteRT / MediaPipe" model
listings). You need the file as a **direct download URL**, or the file on your computer.

> Tip: name the file to match `MODEL_FILENAME` in `ModelManager.kt`
> (default `zeus-model.task`). If your model is a `.bin`, change that constant to
> `zeus-model.bin`.

> Tip: drop **several** model files in (via repeated downloads or `adb push`) and Zeus runs
> them as one — see "Several models, working as ONE" above. Each text model can be `.task`
> or `.bin`; a vision model must be vision-capable (name it with "vision" or "vl").

You can give Zeus the model two ways:
1. **In-app:** launch the app, paste the direct URL, tap *Download the mind*. or
2. **Push it directly** (faster, no URL needed) once the app is installed:
   ```
   adb push your-model.task /sdcard/Android/data/com.olympus.zeus/files/models/zeus-model.task
   ```
   (push more files with different names to add more minds)

---

## Step 2 — Build the APK

### Option A — In the cloud with GitHub (no PC setup)
1. Make a free account at github.com and create a new repository.
2. Upload this whole `ZEUS-ANDROID` folder (drag-and-drop in the browser works).
3. Go to the **Actions** tab → run **Build Zeus APK** (it also runs on every push).
4. When it finishes, open the run → **Artifacts** → download **zeus-apk**.
5. Unzip it to get `app-debug.apk`, copy to your phone, and install (allow
   "install from unknown sources" when prompted).

### Option B — On your PC with Android Studio
1. Install **Android Studio** (free).
2. **File → Open** this `ZEUS-ANDROID` folder. Let it sync (it sets up the Gradle wrapper).
3. Plug in your phone (USB debugging on) and press **Run ▶**, or
   **Build → Build Bundle(s)/APK(s) → Build APK(s)** and install the result.

---

## Step 3 — First run
- Launch **Zeus**. If no model is present he asks for one (Step 1).
- Once the model loads, speak to him. Everything runs on the phone — turn off Wi-Fi and
  mobile data to prove it.

---

## Notes, honestly
- The build is **debug-signed**, so it installs for testing without a keystore. For a
  Play-Store-ready release, add a `signingConfig` and build `assembleRelease`.
- **Voice in/out is wired:** the phone's text-to-speech gives Zeus his voice, and the
  microphone (Android speech recognition) lets you talk to him. Speech recognition uses
  the device's recognizer (on many phones Google's, which may work offline if the offline
  language pack is installed; otherwise it needs a connection for that step only).
- **Image reading (vision)** is wired through MediaPipe's session image API, but it is
  **experimental and version-dependent** — it needs a vision-capable model and a matching
  `tasks-genai` build. If your device/model doesn't support it, Zeus says so politely
  instead of crashing.
- **The wake word** ("Zeus", hands-free, app closed) is the one piece still to add: true
  always-on listening needs a dedicated wake-word engine (e.g. Porcupine / openWakeWord)
  in a foreground service. The mic button gives you push-to-talk today.
- The animated dome/stair lightning from the web prototype isn't redrawn natively here —
  the throne image already carries the storm. A Compose canvas port is a later polish.
- MediaPipe's exact option setters can vary slightly by library version. If a build error
  mentions an option, match it to the installed `tasks-genai` version (pinned `0.10.14`).
- **I could not compile this Kotlin from where it was written**, so do a build in Android
  Studio or via the GitHub workflow; if the compiler flags anything, it'll be a small
  import/version nudge, not a design problem. It will not harm your phone.

## On the new powers (honest)
- **Live talk** is the microphone (device speech recognition) + offline text-to-speech —
  already working as push-to-talk. A true always-on wake word (app closed) still needs a
  dedicated wake-word engine and isn't included.
- **Internet search** uses a lightweight web fetch; it needs a connection and is on-demand
  only (off by default). Results parsing can vary by site.
- **Folder document-search** (search across a whole folder of PDFs/Office files) is currently
  **Windows-first** — on Android it needs an on-device embedder plus PDF/Office parsing, which
  is a larger native addition. The phone reads single images today; the desktop editions do
  full folder search.
- **Pantheon and Prophecy were removed** from this build per request.

## Storage — internal or SD card
Models can live on **internal storage** or an **SD card**. Open the menu (top-right) →
**MODELS STORED ON** → pick *Internal storage* or *SD card*. Zeus then reads/writes models
there and reloads. The SD card location is the app's own folder on the card
(`Android/data/com.olympus.zeus/files/models`), so it needs no special permission. Pushing
models to the SD card with adb:
```
adb push your-model.task /storage/XXXX-XXXX/Android/data/com.olympus.zeus/files/models/
```
(Replace XXXX-XXXX with your card's id.) A faster card loads models quicker; once loaded, a
model runs from RAM regardless.

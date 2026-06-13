package com.olympus.zeus

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val Storm = Color(0xFF06080F)
private val Panel = Color(0xFF0C1322)
private val Gold = Color(0xFFE8C36A)
private val Bolt = Color(0xFF7FD8FF)
private val TextMain = Color(0xFFE9EEF6)
private val Dim = Color(0xFF8794A6)

data class Msg(val who: String, val text: String, val image: androidx.compose.ui.graphics.ImageBitmap? = null)

class MainActivity : ComponentActivity() {

    private lateinit var models: ModelManager
    private var engine: LlmEngine? = null
    private lateinit var voice: VoiceController
    private lateinit var memory: Memory
    private var genJob: Job? = null
    private var recognizer: SpeechRecognizer? = null

    // shared UI state (observed by Compose)
    private val messages = mutableStateListOf<Msg>()
    private var screen by mutableStateOf("checking")     // checking|needModel|loading|ready|error
    private var errorText by mutableStateOf("")
    private var progress by mutableStateOf(0f)
    private var modelUrl by mutableStateOf("")
    private var input by mutableStateOf("")
    private var thinking by mutableStateOf(false)
    private var listening by mutableStateOf(false)
    private var voiceOn by mutableStateOf(true)
    private var internet by mutableStateOf(false)
    private var reloadKey by mutableStateOf(0)
    private var selectedRole by mutableStateOf("everyday")
    private var addNote by mutableStateOf("")
    private var installedSummary by mutableStateOf("")
    private var installedRoles by mutableStateOf(setOf<String>())
    private var installing by mutableStateOf(false)

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening()
            else messages.add(Msg("Zeus", "Allow microphone access so I can hear you."))
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) handleImage(uri)
        }

    private val pickModelFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importModelFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        models = ModelManager(this)
        voice = VoiceController(this)
        memory = Memory(this)
        setContent { ZeusApp() }
    }

    override fun onDestroy() {
        engine?.close(); voice.shutdown(); recognizer?.destroy()
        super.onDestroy()
    }

    // ---- actions ----

    private fun sendText(text: String) {
        run {
            val tt = text.trim()
            if (tt.lowercase().startsWith("remember:")) {
                val fact = tt.substringAfter(":").trim()
                if (fact.isNotEmpty()) {
                    memory.addProfileFact(fact)
                    messages.add(Msg("You", tt))
                    messages.add(Msg("Zeus", "Got it — I'll remember that."))
                    input = ""
                }
                return
            }
        }
        val t = text.trim()
        if (t.isEmpty() || thinking || engine == null) return
        messages.add(Msg("You", t)); input = ""; thinking = true
        genJob = lifecycleScope.launch {
            val reply = try {
                withContext(Dispatchers.Default) {
                    engine!!.also { it.internet = internet }.ask(t)
                }
            } catch (e: Throwable) { "Something went wrong. Please try again." }
            if (!isActive) return@launch          // stopped — discard the answer
            thinking = false
            messages.add(Msg("Zeus", reply))
            if (voiceOn) voice.speak(reply)
        }
    }

    /** Stop a reply in progress: drop the answer, stop the voice, free the UI. */
    private fun stopProcessing() {
        genJob?.cancel()
        genJob = null
        voice.stop()
        thinking = false
    }

    private fun handleImage(uri: Uri) {
        if (thinking || engine == null) return
        val bmp = try {
            contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
        } catch (e: Throwable) { null } ?: return
        messages.add(Msg("You", "", image = bmp.asImageBitmap()))
        thinking = true
        genJob = lifecycleScope.launch {
            val reply = try {
                withContext(Dispatchers.Default) {
                    engine!!.also { it.internet = internet }.askImage("", bmp)
                }
            } catch (e: Throwable) { "My gaze cannot fix upon it just now." }
            if (!isActive) return@launch
            thinking = false
            messages.add(Msg("Zeus", reply))
            if (voiceOn) voice.speak(reply)
        }
    }

    private fun switchStore(id: String) {
        models.setStore(id)
        engine?.close(); engine = null
        screen = "checking"
        reloadKey++   // re-runs model check + load from the chosen storage
    }

    // ---- installing minds (one per role; up to six) ----

    private fun roleFileName(role: String) = "$role.task"

    private fun refreshInstalledSummary() {
        val c = models.catalog()
        installedRoles = c.map { it.name.substringBeforeLast('.').lowercase() }.toSet()
        installedSummary = if (c.isEmpty()) "No minds installed yet."
        else "Installed (${c.size}): " + c.joinToString {
            it.name.substringBeforeLast('.') + " — " + "%.1f".format(it.size / 1e9) + " GB"
        }
    }

    private fun downloadMind() {
        val url = modelUrl.trim()
        if (url.isBlank()) { addNote = "Paste a direct link to a .task file first."; return }
        addNote = ""; installing = true; progress = 0f
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    models.downloadModel(url, roleFileName(selectedRole)) { p -> progress = p }
                }
                modelUrl = ""
                addNote = "The $selectedRole model is installed. Add another, or start chatting."
            } catch (e: Throwable) {
                val m = e.message ?: ""
                addNote = when {
                    "401" in m || "403" in m ->
                        "That model is license-gated (Gemma and Llama are). Open the link in your phone's " +
                        "browser while signed in to Hugging Face, download the file, then use " +
                        "\"Upload a file\" below."
                    else -> "Download failed: ${m.ifBlank { "check the link and your connection." }}"
                }
            }
            installing = false; progress = 0f
            refreshInstalledSummary()
        }
    }

    private fun importModelFile(uri: Uri) {
        addNote = ""; installing = true; progress = 0f
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    var total = -1L
                    try {
                        contentResolver.query(uri, null, null, null, null)?.use { c ->
                            val i = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                            if (i >= 0 && c.moveToFirst()) total = c.getLong(i)
                        }
                    } catch (e: Exception) { /* size unknown is fine */ }
                    contentResolver.openInputStream(uri)?.use { input ->
                        models.importStream(input, roleFileName(selectedRole), total) { p -> progress = p }
                    } ?: throw RuntimeException("could not open that file")
                }
                addNote = "The $selectedRole model is installed. Add another, or start chatting."
            } catch (e: Throwable) {
                addNote = "Import failed: ${e.message ?: "could not read the file."}"
            }
            installing = false; progress = 0f
            refreshInstalledSummary()
        }
    }

    private fun toggleMic() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) startListening()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            messages.add(Msg("Zeus", "Speech recognition is not available on this device.")); return
        }
        if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) { listening = false }
            override fun onResults(results: Bundle?) {
                listening = false
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) sendText(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        voice.stop()
        recognizer?.startListening(intent)
    }

    private fun loadEngineThen(onReady: () -> Unit) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    engine = LlmEngine(this@MainActivity, models, memory).also { it.preload() }
                }
                onReady()
            } catch (e: Throwable) {
                errorText = e.message ?: "Could not load the model."; screen = "error"
            }
        }
    }

    // ---- UI ----

    @Composable
    fun ZeusApp() {
        val listState = rememberLazyListState()

        LaunchedEffect(reloadKey) {
            refreshInstalledSummary()
            messages.clear()
            if (models.isAnyModelPresent()) {
                screen = "loading"
                loadEngineThen {
                    messages.add(Msg("Zeus",
                        "Hi, I am Zeus, your offline assistant. What can I help you with?"))
                    screen = "ready"
                    if (voiceOn) voice.speak(messages.last().text)
                }
            } else screen = "needModel"
        }
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }

        MaterialTheme(colorScheme = darkColorScheme(primary = Gold, background = Storm, surface = Panel)) {
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF0B1024), Storm, Color(0xFF0A0712))))) {
                val showThrone = screen == "ready" || screen == "loading"
                if (showThrone) {
                    Image(painter = painterResource(R.drawable.throne), contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    // Dark scrim so chat text stays readable over the artwork.
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        listOf(Color(0xB30B1024), Color(0x800A0712), Color(0xF00A0712)))))
                    LightningFlash()
                }
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Header()
                    Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                        when (screen) {
                            "checking", "loading" -> CenteredNote(
                                if (screen == "loading") "Loading the model…" else "Thinking…")
                            "needModel" -> NeedModel(listState)
                            "error" -> CenteredNote("Could not start:\n$errorText", isError = true)
                            "ready" -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
                                items(messages) { m -> Bubble(m) }
                                if (thinking) item { Bubble(Msg("Zeus", "…")) }
                            }
                        }
                    }
                    if (screen == "ready") InputRow()
                }
            }
        }
    }

    @Composable
    private fun Header() {
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("ZEUS", color = Gold, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 6.sp)
            Spacer(Modifier.weight(1f))
            Text(if (listening) "LISTENING…" else if (internet) "ONLINE" else "OFFLINE",
                color = if (listening) Bolt else Dim, fontSize = 9.sp, letterSpacing = 2.sp)
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.Tune, contentDescription = "Settings", tint = Gold)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (voiceOn) "Voice: on" else "Voice: off", color = TextMain) },
                    onClick = { voiceOn = !voiceOn; if (!voiceOn) voice.stop() })
                DropdownMenuItem(
                    text = { Text(if (internet) "Internet search: on" else "Internet search: off", color = TextMain) },
                    onClick = { internet = !internet })
                HorizontalDivider()
                Text("  VOICE", color = Gold, fontSize = 10.sp,
                    modifier = Modifier.padding(8.dp))
                val sample = { if (voiceOn) voice.speak("This is my voice.") else Unit }
                DropdownMenuItem(text = { Text("Deeper", color = TextMain) },
                    onClick = { voice.deeper("Zeus"); sample() })
                DropdownMenuItem(text = { Text("Higher", color = TextMain) },
                    onClick = { voice.higher("Zeus"); sample() })
                DropdownMenuItem(text = { Text("Slower", color = TextMain) },
                    onClick = { voice.slower("Zeus"); sample() })
                DropdownMenuItem(text = { Text("Faster", color = TextMain) },
                    onClick = { voice.faster("Zeus"); sample() })
                DropdownMenuItem(text = { Text("Change voice (try another)", color = TextMain) },
                    onClick = { voice.cycleVoice("Zeus"); sample() })
                DropdownMenuItem(text = { Text("Test voice", color = TextMain) },
                    onClick = { voice.speak("This is my voice.") })
                DropdownMenuItem(text = { Text("Reset voice", color = Bolt) },
                    onClick = { voice.resetGod("Zeus"); sample() })
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Add a model", color = TextMain) },
                    onClick = { menu = false; refreshInstalledSummary(); addNote = ""; screen = "needModel" })
                DropdownMenuItem(
                    text = { Text("Wipe memory", color = Bolt) },
                    onClick = {
                        menu = false
                        memory.wipe(); engine?.resetHistory()
                        messages.add(Msg("Zeus", "Memory cleared. We're starting fresh."))
                    })
                HorizontalDivider()
                Text("  MODELS STORED ON", color = Gold, fontSize = 10.sp, modifier = Modifier.padding(8.dp))
                models.stores().forEach { store ->
                    DropdownMenuItem(
                        text = { Text(store.label, color = if (store.id == models.currentStoreId()) Gold else TextMain) },
                        onClick = { menu = false; switchStore(store.id) })
                }
            }
        }
    }

    @Composable
    private fun LightningFlash() {
        val transition = rememberInfiniteTransition(label = "lightning")
        val alpha by transition.animateFloat(
            initialValue = 0f, targetValue = 0f,
            animationSpec = infiniteRepeatable(animation = keyframes {
                durationMillis = 6500
                0f at 0; 0f at 700; 0.55f at 780; 0.05f at 860; 0.65f at 920; 0f at 1120; 0f at 6500
            }), label = "flash")
        Box(Modifier.fillMaxSize().alpha(alpha).background(Brush.verticalGradient(
            listOf(Color(0xFFCDA8FF), Color(0x33B98CFF), Color(0x00000000)))))
    }

    @Composable
    private fun CenteredNote(text: String, isError: Boolean = false) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text, color = if (isError) Bolt else Dim, textAlign = TextAlign.Center, fontSize = 15.sp)
        }
    }

    @Composable
    private fun NeedModel(unused: Any?) {
        val roles = listOf("quick", "everyday", "deep", "reasoning", "coding", "vision")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center) {
            Text("Give Zeus his minds", color = Gold, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Your device has about ${"%.1f".format(models.deviceRamGb())} GB of memory. " +
                "Recommended: ${models.recommendedTier()}", color = TextMain, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(installedSummary, color = Dim, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Text("Choose which mind this file becomes (one model needed; up to six):",
                color = TextMain, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            roles.chunked(3).forEach { rowRoles ->
                Row {
                    rowRoles.forEach { r ->
                        FilterChip(selected = selectedRole == r, onClick = { selectedRole = r },
                            label = { Text(r, fontSize = 12.sp) },
                            leadingIcon = if (r in installedRoles) ({
                                Icon(Icons.Filled.Check, contentDescription = "installed",
                                    tint = Gold, modifier = Modifier.size(14.dp))
                            }) else null)
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Two ways to install each mind: DOWNLOAD by direct link (works for ungated " +
                "models: Qwen, DeepSeek-R1 distill, Phi, TinyLlama — copy the .task file link from " +
                "its Hugging Face litert-community page), or UPLOAD a model file already on this " +
                "device (works for everything — including gated Gemma/Llama you downloaded in " +
                "your browser while signed in).", color = Dim, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = modelUrl, onValueChange = { modelUrl = it },
                placeholder = { Text("https://…/model.task", color = Dim) }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold,
                    unfocusedBorderColor = Color(0x33FFFFFF), focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            if (installing) {
                LinearProgressIndicator(progress = { progress }, color = Bolt, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("Installing the $selectedRole mind… ${(progress * 100).toInt()}%",
                    color = Dim, fontSize = 12.sp)
            } else {
                Button(onClick = { downloadMind() },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold)) {
                    Text("Download as the $selectedRole mind", color = Storm, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { pickModelFile.launch("*/*") }) {
                    Text("Upload a file as the $selectedRole mind", color = Bolt)
                }
                if (models.isAnyModelPresent()) {
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { reloadKey++ },
                        colors = ButtonDefaults.buttonColors(containerColor = Panel)) {
                        Text("Start chatting ⚡", color = Gold, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (addNote.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(addNote, color = if ("failed" in addNote || "gated" in addNote) Bolt else Gold,
                    fontSize = 12.sp)
            }
        }
    }

    @Composable
    private fun Bubble(m: Msg) {
        val mine = m.who == "You"
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
            Column(Modifier.widthIn(max = 320.dp)
                .background(if (mine) Color(0x22E8C36A) else Color(0x117FD8FF), RoundedCornerShape(14.dp))
                .padding(12.dp)) {
                if (!mine) Text(m.who.uppercase(), color = Gold, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                if (m.image != null) {
                    Image(bitmap = m.image, contentDescription = null,
                        modifier = Modifier.widthIn(max = 200.dp), contentScale = ContentScale.Fit)
                }
                if (m.text.isNotEmpty())
                    Text(m.text, color = TextMain, fontSize = 15.sp)
            }
        }
    }

    @Composable
    private fun InputRow() {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(onClick = { pickImage.launch("image/*") },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Panel)) {
                Text("📷", fontSize = 20.sp)
            }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { toggleMic() },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (listening) Bolt else Panel)) {
                Text("🎤", fontSize = 20.sp)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(value = input, onValueChange = { input = it },
                placeholder = { Text("Speak, and Zeus shall answer…", color = Dim) },
                modifier = Modifier.weight(1f), enabled = !thinking, maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendText(input) }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Gold,
                    unfocusedBorderColor = Color(0x33FFFFFF), focusedTextColor = TextMain,
                    unfocusedTextColor = TextMain))
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = { if (thinking) stopProcessing() else sendText(input) },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (thinking) Bolt else Gold)) {
                Text(if (thinking) "⏹" else "➤", fontSize = 19.sp, color = Storm)
            }
        }
    }
}

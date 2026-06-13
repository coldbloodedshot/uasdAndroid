package com.uasd.main

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log

class DictationManager(
    private val context: Context,
    private val callback: DictationCallback
) {

    interface DictationCallback {
        fun onGradeRecognized(matricula: String, nombre: String, grade: Double)
        fun onBulkGradesRecognized(updates: List<GradeUpdate>)
        fun onStatusChanged(statusText: String, color: Int)
        fun onVoskModelLoaded()
        fun onDictationStopped()
        fun onError(errorMsg: String)
        fun onSoundSuccess()
        fun onSoundError()
    }

    data class GradeUpdate(val id: String, val grade: Double)

    private val PREFS_NAME = "uasd_prefs"
    private val KEY_DICTATION_MODE = "dictation_mode"
    private val KEY_USE_GEMINI_CORRECTION = "use_gemini_correction"
    private val MODE_VOSK = "vosk"
    private val MODE_NATIVE = "native"
    private val MODE_GEMINI = "gemini"

    var isDictationMode = false
        private set

    private val managerJob = SupervisorJob()
    private val managerScope = CoroutineScope(managerJob + Dispatchers.Main.immediate)
    private var released = false

    // Vosk
    private var voskModel: Model? = null
    private var voskRecognizer: Recognizer? = null
    private var voskAudioRecord: AudioRecord? = null
    private var voskAudioJob: Job? = null
    private var isVoskLoading = false
    private var voskListeningActive = false
    private var voskFinalBuffer = StringBuilder()

    // Gemini fallback cooldown
    private var geminiFallbackInProgress = false
    private var lastGeminiFallbackTime = 0L
    private val GEMINI_FALLBACK_COOLDOWN_MS = 3000L

    // Native
    private var nativeSpeechRecognizer: SpeechRecognizer? = null

    // Native — circular audio buffer (4 seconds @ 16 kHz, 16-bit mono = 128 000 bytes)
    private val SAMPLE_RATE = 16000
    private val BUFFER_SECONDS = 4
    private val BUFFER_SIZE = SAMPLE_RATE * 2 * BUFFER_SECONDS // 128 000 bytes
    private val audioCircularBuffer = ByteArray(BUFFER_SIZE)
    private var circularBufferIndex = 0          // write-head (wraps around)
    private var circularBufferFilled = false      // true once the buffer has been written at least once fully
    private var currentEstudiantesList: List<Estudiante> = emptyList()

    // Gemini
    private var audioRecorder: AudioRecorder? = null
    private var generativeModel: GenerativeModel? = null
    private var geminiStartTime: Long = 0


    fun getDictationMode(): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DICTATION_MODE, MODE_VOSK) ?: MODE_VOSK
    }

    fun isGeminiCorrectionEnabled(): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_GEMINI_CORRECTION, true)
    }

    fun setGeminiCorrectionEnabled(enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USE_GEMINI_CORRECTION, enabled).apply()
    }

    fun initVoskModel() {
        if (released || voskModel != null || isVoskLoading) return
        isVoskLoading = true
        callback.onStatusChanged("Cargando Vosk...", android.graphics.Color.DKGRAY)

        StorageService.unpack(context, "model-es", "model",
            { model: Model ->
                if (released) return@unpack
                voskModel = model
                isVoskLoading = false
                Log.d("DictationManager", "Modelo Vosk cargado exitosamente")
                callback.onVoskModelLoaded()
                if (isDictationMode) {
                    startVoskDictation(currentEstudiantesList)
                }
            },
            { exception: Exception ->
                if (released) return@unpack
                isVoskLoading = false
                Log.e("DictationManager", "Error cargando modelo Vosk: ${exception.message}")
                callback.onError("Error cargando modelo: ${exception.message}")
            })
    }

    fun startDictation(estudiantesList: List<Estudiante>) {
        if (released) return
        val mode = getDictationMode()
        Log.d("DictationManager", "Iniciando dictado con modo: $mode")
        isDictationMode = true
        currentEstudiantesList = estudiantesList
        
        when (mode) {
            MODE_VOSK -> startVoskDictation(estudiantesList)
            MODE_NATIVE -> startNativeDictation(estudiantesList)
            MODE_GEMINI -> startGeminiDictation()
        }
    }

    fun stopDictation() {
        if (!isDictationMode) return
        val mode = getDictationMode()
        isDictationMode = false

        when (mode) {
            MODE_VOSK -> stopVoskDictation()
            MODE_NATIVE -> stopNativeDictation()
            MODE_GEMINI -> stopGeminiDictation()
        }
        if (!released) {
            callback.onDictationStopped()
        }
    }

    // --- VOSK ---
    @SuppressLint("MissingPermission")
    private fun startVoskDictation(estudiantesList: List<Estudiante>) {
        if (released) return
        if (voskModel == null) {
            if (!isVoskLoading) {
                initVoskModel()
            }
            return
        }

        callback.onStatusChanged("DICTADO VOSK (Escuchando...)", android.graphics.Color.RED)
        voskFinalBuffer.setLength(0)
        resetAudioBuffer()

        try {
            val nombres = estudiantesList.flatMap { 
                it.nombre.lowercase().replace(",", "").replace(".", "").split(" ") 
            }.distinct().filter { it.length > 2 }
            
            val nombresCompletos = estudiantesList.map { it.nombre.lowercase() }
            val matriculas = estudiantesList.map { it.matricula }
            val numerosDigitos = (0..100).map { it.toString() }
            val numerosPalabras = listOf(
                "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
                "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte",
                "veintiuno", "veintidós", "veintitres", "veinticuatro", "veinticinco", "veintiseis", "veintisiete", "veintiocho", "veintinueve", "treinta",
                "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa", "cien", "punto", "coma"
            )
            
            //val grammarList = (nombres + nombresCompletos + matriculas + numerosDigitos + numerosPalabras+listOf("Asencio","emanuel","en manuel","emmanuel")+ listOf("[unk]")).distinct()
            val grammarList = (nombres + nombresCompletos + matriculas + numerosDigitos + numerosPalabras+listOf("asensio","emanuel","ama de ris","ses pe des","ke u ri", "dan al vin","i von ni")).distinct()
            //val grammarList = (nombres + nombresCompletos + matriculas + numerosDigitos + numerosPalabras).distinct()
            val grammarJson = org.json.JSONArray(grammarList).toString()

            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) {
                callback.onError("No se pudo inicializar el micrófono para Vosk.")
                stopDictation()
                return
            }

            val readBufferSize = maxOf(minBufferSize, 4096)
            voskRecognizer = Recognizer(voskModel, SAMPLE_RATE.toFloat(), grammarJson)

//            val grammar = assets.open("grammar.json").bufferedReader().use { it.readText() }
//            voskRecognizer.setGrammar(grammar)

            voskAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                readBufferSize
            )
            voskAudioRecord?.startRecording()
            voskListeningActive = true

            voskAudioJob = managerScope.launch(Dispatchers.IO) {
                val buffer = ByteArray(readBufferSize)
                var lastPartialText = ""
                while (isDictationMode && voskListeningActive && voskAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = voskAudioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read <= 0) continue

                    writeToCircularBuffer(buffer, read)
                    val recognizer = voskRecognizer ?: break
                    val hasFinalResult = recognizer.acceptWaveForm(buffer, read)

                    if (hasFinalResult) {
                        val text = JSONObject(recognizer.result).optString("text")
                        Log.d("DictationManager", "Vosk reconoció: \"$text\"")
                        if (text.isNotEmpty()) {
                            lastPartialText = ""
                            if (voskFinalBuffer.isNotEmpty()) voskFinalBuffer.append(" ")
                            voskFinalBuffer.append(text)
                            val recognized = withContext(Dispatchers.Main) {
                                procesarTextoDictado(voskFinalBuffer.toString(), true, estudiantesList)
                            }
                            if (!recognized) {
                                triggerGeminiFallback()
                            }
                        }
                    } else {
                        val partial = JSONObject(recognizer.partialResult).optString("partial")
                        if (partial.isNotEmpty() && partial != lastPartialText) {
                            lastPartialText = partial
                            val currentTotal = if (voskFinalBuffer.isEmpty()) partial else "${voskFinalBuffer} $partial"
                            withContext(Dispatchers.Main) {
                                procesarTextoDictado(currentTotal, false, estudiantesList)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DictationManager", "Error iniciando Vosk con AudioRecord: ${e.message}", e)
            voskListeningActive = false
            stopDictation()
        }
    }

    private fun stopVoskDictation() {
        voskListeningActive = false
        voskAudioJob?.cancel()
        voskAudioJob = null
        try {
            if (voskAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                voskAudioRecord?.stop()
            }
        } catch (_: IllegalArgumentException) {
            Log.w("DictationManager", "Vosk: AudioRecord ya detenido")
        } catch (e: Exception) {
            Log.e("DictationManager", "Error deteniendo Vosk: ${e.message}")
        }
        voskAudioRecord?.release()
        voskAudioRecord = null
        voskRecognizer?.close()
        voskRecognizer = null
        resetAudioBuffer()
    }

    // --- NATIVE SPEECH RECOGNIZER ---
    private fun startNativeDictation(estudiantesList: List<Estudiante>) {
        if (nativeSpeechRecognizer != null) return

        callback.onStatusChanged("DICTADO NATIVO (Escuchando...)", android.graphics.Color.RED)
        nativeSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        nativeSpeechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val isNoMatch = error == SpeechRecognizer.ERROR_NO_MATCH
                val isTimeout = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                Log.w("DictationManager", "SpeechRecognizer.onError: code=$error isNoMatch=$isNoMatch isTimeout=$isTimeout")

                if (isDictationMode && (isNoMatch || isTimeout)) {
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.postDelayed({
                        if (isDictationMode) nativeSpeechRecognizer?.startListening(intent)
                    }, 500)
                } else {
                    stopDictation()
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val textoReconocido = matches[0]
                    Log.d("DictationManager", "onResults: \"$textoReconocido\"")
                    procesarTextoDictado(textoReconocido, true, estudiantesList)
                }
                if (isDictationMode) {
                    nativeSpeechRecognizer?.startListening(intent)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    procesarTextoDictado(matches[0], false, estudiantesList)
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        nativeSpeechRecognizer?.startListening(intent)
    }

    private fun stopNativeDictation() {
        nativeSpeechRecognizer?.stopListening()
        nativeSpeechRecognizer?.destroy()
        nativeSpeechRecognizer = null
    }

    // --- CIRCULAR AUDIO BUFFER ---

    @Synchronized
    private fun resetAudioBuffer() {
        circularBufferIndex = 0
        circularBufferFilled = false
    }

    /** Writes [length] bytes from [data] into the circular buffer. Thread‑safe via @Synchronized. */
    @Synchronized
    private fun writeToCircularBuffer(data: ByteArray, length: Int) {
        val remaining = BUFFER_SIZE - circularBufferIndex
        if (length <= remaining) {
            System.arraycopy(data, 0, audioCircularBuffer, circularBufferIndex, length)
            circularBufferIndex += length
            if (circularBufferIndex >= BUFFER_SIZE) {
                circularBufferIndex = 0
                circularBufferFilled = true
            }
        } else {
            System.arraycopy(data, 0, audioCircularBuffer, circularBufferIndex, remaining)
            val leftover = length - remaining
            System.arraycopy(data, remaining, audioCircularBuffer, 0, leftover)
            circularBufferIndex = leftover
            circularBufferFilled = true
        }
    }

    /**
     * Returns the contents of the circular buffer in chronological order.
     * If the buffer hasn't been filled yet, returns only what has been written.
     */
    @Synchronized
    private fun getChronologicalAudio(): ByteArray {
        return if (!circularBufferFilled) {
            // Buffer not yet full — return whatever was written
            audioCircularBuffer.copyOf(circularBufferIndex)
        } else {
            // Oldest data starts right after the write-head
            val result = ByteArray(BUFFER_SIZE)
            val tailLen = BUFFER_SIZE - circularBufferIndex
            System.arraycopy(audioCircularBuffer, circularBufferIndex, result, 0, tailLen)
            System.arraycopy(audioCircularBuffer, 0, result, tailLen, circularBufferIndex)
            result
        }
    }

    /**
     * Writes raw PCM data to a temporary WAV file with a valid 44-byte RIFF header.
     * Returns the [File] or null if writing fails.
     */
    private fun saveBufferToWavFile(pcmData: ByteArray): File? {
        if (pcmData.isEmpty()) return null
        return try {
            val wavFile = File(context.cacheDir, "fallback_audio_${System.currentTimeMillis()}.wav")
            FileOutputStream(wavFile).use { fos ->
                val numChannels: Short = 1
                val sampleRateHz = SAMPLE_RATE
                val bitsPerSample: Short = 16
                val byteRate = sampleRateHz * numChannels * (bitsPerSample / 8)
                val blockAlign = (numChannels * (bitsPerSample / 8)).toShort()
                val dataLen = pcmData.size
                val chunkSize = 36 + dataLen

                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                    // RIFF chunk
                    put("RIFF".toByteArray())
                    putInt(chunkSize)
                    put("WAVE".toByteArray())
                    // fmt  sub-chunk
                    put("fmt ".toByteArray())
                    putInt(16)                    // sub-chunk size (PCM)
                    putShort(1)                   // AudioFormat = PCM
                    putShort(numChannels)
                    putInt(sampleRateHz)
                    putInt(byteRate)
                    putShort(blockAlign)
                    putShort(bitsPerSample)
                    // data sub-chunk
                    put("data".toByteArray())
                    putInt(dataLen)
                }
                fos.write(header.array())
                fos.write(pcmData)
            }
            Log.d("DictationManager", "WAV guardado: ${wavFile.absolutePath} (${pcmData.size} bytes PCM)")
            wavFile
        } catch (e: IOException) {
            Log.e("DictationManager", "Error guardando WAV: ${e.message}")
            null
        }
    }

    /**
     * Triggered when Vosk produces text that the local matcher cannot assign.
     * Saves the circular buffer as a WAV file and sends it to Gemini for fallback recognition.
     */
    private fun triggerGeminiFallback() {
        if (!isGeminiCorrectionEnabled()) {
            Log.d("DictationManager", "Corrección Gemini desactivada — omitiendo fallback")
            return
        }
        val now = System.currentTimeMillis()
        if (geminiFallbackInProgress) {
            Log.d("DictationManager", "Fallback Gemini ya en progreso — omitiendo")
            return
        }
        if (now - lastGeminiFallbackTime < GEMINI_FALLBACK_COOLDOWN_MS) {
            Log.d("DictationManager", "Fallback Gemini en cooldown (${now - lastGeminiFallbackTime}ms) — omitiendo")
            return
        }
        geminiFallbackInProgress = true
        lastGeminiFallbackTime = now
        managerScope.launch(Dispatchers.IO) {
            try {
                val pcmData = getChronologicalAudio()
                if (pcmData.isEmpty()) {
                    Log.d("DictationManager", "Buffer vacío — omitiendo fallback Gemini")
                    return@launch
                }
                val wavFile = saveBufferToWavFile(pcmData)
                if (wavFile == null) {
                    Log.w("DictationManager", "No se pudo guardar WAV — omitiendo fallback Gemini")
                    return@launch
                }
                val alumnos = currentEstudiantesList
                if (alumnos.isEmpty()) {
                    Log.d("DictationManager", "Lista de alumnos vacía — omitiendo fallback Gemini")
                    return@launch
                }
                val alumnosPrompt = alumnos.joinToString("\n") { "ID: ${it.matricula} | Nombre: ${it.nombre}" }
                Log.i("DictationManager", "🤖 Fallback Gemini activado — enviando ${wavFile.name} (${pcmData.size} bytes PCM)")
                withContext(Dispatchers.Main) {
                    callback.onStatusChanged("🤖 Consultando Gemini...", android.graphics.Color.rgb(255, 140, 0))
                    procesarAudioConGeminiConAlumnos(wavFile, alumnosPrompt)
                }
            } finally {
                geminiFallbackInProgress = false
            }
        }
    }

    // --- GEMINI (HOLD-TO-TALK) ---
    private fun startGeminiDictation() {
        if (audioRecorder == null) audioRecorder = AudioRecorder(context)
        callback.onStatusChanged("GEMINI (Grabando...)", android.graphics.Color.BLUE)
        geminiStartTime = System.currentTimeMillis()
        audioRecorder?.startRecording("gemini_audio")
    }

    private fun stopGeminiDictation() {
        val duration = System.currentTimeMillis() - geminiStartTime
        val audioFile = audioRecorder?.stopRecording()

        if (duration > 500 && audioFile != null && audioFile.exists()) {
            procesarAudioConGemini(audioFile)
        } else {
            Log.d("DictationManager", "Grabación ignorada por ser demasiado corta ($duration ms)")
        }
    }

    private fun procesarAudioConGemini(audioFile: File) {
        val alumnos = currentEstudiantesList
        if (alumnos.isEmpty()) {
            callback.onError("Lista de alumnos vacía para Gemini.")
            return
        }

        val alumnosPrompt = alumnos.joinToString("\n") { "ID: ${it.matricula} | Nombre: ${it.nombre}" }
        procesarAudioConGeminiConAlumnos(audioFile, alumnosPrompt)
    }

    fun procesarAudioConGeminiConAlumnos(audioFile: File, alumnosContextPrompt: String) {
        val geminiApiKey = BuildConfig.GEMINI_API_KEY
        if (geminiApiKey.isEmpty()) {
            callback.onError("API Key de Gemini no configurada.")
            return
        }

        if (generativeModel == null) {
            generativeModel = GenerativeModel(
                modelName = "gemini-flash-latest",
                apiKey = geminiApiKey
            )
        }

        val systemPrompt = """
            Eres un asistente de calificación para la UASD. 
            Recibirás un audio y una lista de estudiantes. 
            Tu objetivo es identificar qué nota se le asigna a qué estudiante.
            
            REGLAS:
            1. Solo devuelve un objeto JSON válido.
            2. Formato: {"updates": [{"id": "ID_ESTUDIANTE", "grade": NOTA_NUMERICA}], "errors": [{"query": "texto", "grade": NOTA}]}
            3. Si no escuchas una nota clara para alguien, ignóralo.
            4. Si escuchas algo que parece un nombre y nota pero NO está en la lista, ponlo en "errors".
            5. La lista de estudiantes es:
            $alumnosContextPrompt
        """.trimIndent()

        managerScope.launch(Dispatchers.IO) {
            try {
                val inputAudio = FileInputStream(audioFile).use { it.readBytes() }
                if (inputAudio.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        callback.onError("El archivo de audio está vacío.")
                    }
                    return@launch
                }

                // Determine MIME type from file extension (wav vs mpeg)
                val mimeType = when {
                    audioFile.name.endsWith(".wav", ignoreCase = true) -> "audio/wav"
                    audioFile.name.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
                    else -> "audio/wav"
                }

                val content = content {
                    text(systemPrompt)
                    blob(mimeType, inputAudio)
                    text("Procesa este audio y devuelve las actualizaciones de notas.")
                }

                val response = generativeModel?.generateContent(content)
                val responseText = response?.text

                Log.d("DictationManager", "Respuesta cruda de Gemini: $responseText")

                if (responseText.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        callback.onError("Gemini no devolvió ningún texto. Intenta de nuevo.")
                    }
                    return@launch
                }

                val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(responseText)
                val jsonString = jsonMatch?.value ?: responseText
                
                Log.d("DictationManager", "String JSON extraído: $jsonString")

                withContext(Dispatchers.Main) {
                    aplicarActualizacionesGemini(jsonString)
                }
            } catch (e: com.google.ai.client.generativeai.type.InvalidStateException) {
                Log.e("DictationManager", "Gemini estado inválido: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback.onError("Gemini: estado inválido. Verifica la API Key.")
                }
                        } catch (e: com.google.ai.client.generativeai.type.ServerException) {
                Log.e("DictationManager", "Gemini server error: ${e.message}")
                if (e.message?.contains("PERMISSION_DENIED") == true || e.message?.contains("\"code\": 403") == true) {
                    withContext(Dispatchers.Main) {
                        callback.onError("Gemini API key appears to be compromised or revoked (error 403). Please generate a new API key and update BuildConfig.GEMINI_API_KEY.")
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        callback.onError("Gemini server error: ${e.message?.take(100)}")
                    }
                }
            } catch (e: com.google.ai.client.generativeai.type.GoogleGenerativeAIException) {
                Log.e("DictationManager", "Gemini error de servidor: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback.onError("Gemini: error del servidor (${e.message?.take(80)}).")
                }

            } catch (e: IOException) {
                Log.e("DictationManager", "Error de I/O leyendo audio: ${e.message}")
                withContext(Dispatchers.Main) {
                    callback.onError("Error leyendo el archivo de audio.")
                }
            } catch (e: Exception) {
                Log.e("DictationManager", "Error inesperado en Gemini: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    callback.onError("Error inesperado: ${e.message?.take(100)}.")
                }
            } finally {
                // Clean up temporary WAV fallback files to avoid filling the cache directory
                if (audioFile.name.startsWith("fallback_audio_") && audioFile.exists()) {
                    audioFile.delete()
                }
                // Restore the "listening" status in the UI once Gemini is done (success or error)
                if (isDictationMode) {
                    withContext(Dispatchers.Main) {
                        val status = if (modeIsVosk()) "DICTADO VOSK (Escuchando...)" else "DICTADO NATIVO (Escuchando...)"
                        callback.onStatusChanged(status, android.graphics.Color.RED)
                    }
                }
            }
        }
    }

    private fun aplicarActualizacionesGemini(jsonString: String) {
        Log.d("DictationManager", "Intentando parsear JSON de Gemini: $jsonString")
        try {
            val obj = JSONObject(jsonString)
            if (obj.has("errors")) {
                val errors = obj.getJSONArray("errors")
                if (errors.length() > 0) {
                    callback.onSoundError()
                }
            }

            val updates = obj.getJSONArray("updates")
            val listUpdates = mutableListOf<GradeUpdate>()
            for (i in 0 until updates.length()) {
                val update = updates.getJSONObject(i)
                val studentId = update.getString("id")
                val grade = update.getDouble("grade")
                listUpdates.add(GradeUpdate(studentId, grade))
            }
            if (listUpdates.isNotEmpty()) callback.onSoundSuccess()
            callback.onBulkGradesRecognized(listUpdates)
        } catch (e: Exception) {
            Log.e("DictationManager", "Error parseando JSON: ${e.message}", e)
            callback.onError("Error interpretando respuesta de Gemini")
        }
    }

    // --- PROCESAMIENTO TEXTO / EXPRESIONES REGULARES ---
    // Returns true if at least one student grade was successfully matched, false otherwise.
    private fun procesarTextoDictado(texto: String, esFinal: Boolean, estudiantesList: List<Estudiante>): Boolean {
        if (texto.isEmpty()) return false
        
        val wordToNum = mapOf(
            "cero" to 0, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5,
            "seis" to 6, "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10,
            "once" to 11, "doce" to 12, "trece" to 13, "catorce" to 14, "quince" to 15,
            "dieciséis" to 16, "diecisiete" to 17, "dieciocho" to 18, "diecinueve" to 19, "veinte" to 20,
            "veintiuno" to 21, "veintidós" to 22, "veintitres" to 23, "veinticuatro" to 24, "veinticinco" to 25,
            "veintiseis" to 26, "veintisiete" to 27, "veintiocho" to 28, "veintinueve" to 29, "treinta" to 30,
            "cuarenta" to 40, "cincuenta" to 50, "sesenta" to 60, "setenta" to 70, "ochenta" to 80, "noventa" to 90, "cien" to 100
        )

        val numWordsRegex = wordToNum.keys.joinToString("|")
        val namePattern = "(?:(?!\\b(?:$numWordsRegex|\\d+)\\b).)+"
        val blockRegex = Regex("($namePattern)\\s+\\b(\\d{1,3}([.,]\\d+)?|$numWordsRegex)\\b", RegexOption.IGNORE_CASE)
        val matches = blockRegex.findAll(texto).toList()
        
        var lastMatchEnd = -1
        var alumnoReconocido = false
        
        for (m in matches) {
            val query = m.groupValues[1].trim()
            val gradeStr = m.groupValues[2].lowercase()
            
            if (query.length < 3) {
                lastMatchEnd = m.range.last
                continue
            }

            val grade: Double = if (gradeStr.contains(Regex("\\d"))) {
                gradeStr.replace(',', '.').toDoubleOrNull() ?: continue
            } else {
                wordToNum[gradeStr]?.toDouble() ?: continue
            }
            
            val namesList = estudiantesList.map { it.nombre }
            val matchResult = FuzzyMatcher.findBestMatch(query, namesList, "name")
            
            val idsList = estudiantesList.map { it.matricula }
            val idMatchResult = FuzzyMatcher.findBestMatch(query, idsList, "id")
            
            var bestStudentId: String? = null
            var bestScore = 0.0

            if (matchResult != null) {
                if (matchResult.nivelCoincidencia >= 0.5) {
                    bestStudentId = estudiantesList[matchResult.index].matricula
                    bestScore = matchResult.nivelCoincidencia
                }
            }

            if (idMatchResult != null) {
                if (idMatchResult.nivelCoincidencia >= 0.8) {
                    if (idMatchResult.nivelCoincidencia > bestScore) {
                        bestStudentId = estudiantesList[idMatchResult.index].matricula
                    }
                }
            }

            if (bestStudentId != null) {
                val studentObj = estudiantesList.find { it.matricula == bestStudentId }
                if (studentObj != null) {
                    callback.onGradeRecognized(studentObj.matricula, studentObj.nombre, grade)
                    alumnoReconocido = true
                }
                lastMatchEnd = m.range.last
            } else {
                if (esFinal) callback.onSoundError()
                lastMatchEnd = m.range.last
            }
        }
        
        if (lastMatchEnd != -1 && isDictationMode && modeIsVosk()) {
            val rest = if (lastMatchEnd + 1 < voskFinalBuffer.length) {
                voskFinalBuffer.substring(lastMatchEnd + 1)
            } else ""
            voskFinalBuffer.setLength(0)
            voskFinalBuffer.append(rest.trim())
        }
        return alumnoReconocido
    }

    private fun modeIsVosk(): Boolean {
        return getDictationMode() == MODE_VOSK
    }

    fun release() {
        if (released) return
        released = true
        isDictationMode = false
        isVoskLoading = false
        stopVoskDictation()
        stopNativeDictation()
        try {
            audioRecorder?.stopRecording()
        } catch (_: Exception) {
        }
        managerJob.cancel()
    }
}

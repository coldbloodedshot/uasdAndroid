package com.uasd.main

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import org.vosk.android.RecognitionListener as VoskRecognitionListener

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
    private var voskSpeechService: SpeechService? = null
    private var isVoskLoading = false
    private var voskListeningActive = false
    private var voskFinalBuffer = StringBuilder()

    // Native
    private var nativeSpeechRecognizer: SpeechRecognizer? = null

    // Gemini
    private var audioRecorder: AudioRecorder? = null
    private var generativeModel: GenerativeModel? = null
    private var geminiStartTime: Long = 0

    fun getDictationMode(): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DICTATION_MODE, MODE_VOSK) ?: MODE_VOSK
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
        isDictationMode = true
        
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
    private fun startVoskDictation(estudiantesList: List<Estudiante>) {
        if (released) return
        if (voskModel == null) {
            isDictationMode = false
            if (isVoskLoading) {
                callback.onError("El modelo aún se está cargando...")
            } else {
                initVoskModel()
            }
            return
        }

        callback.onStatusChanged("DICTADO VOSK (Escuchando...)", android.graphics.Color.RED)
        voskFinalBuffer.setLength(0)

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
            
            val grammarList = (nombres + nombresCompletos + matriculas + numerosDigitos + numerosPalabras + listOf("[unk]")).distinct()
            val grammarJson = org.json.JSONArray(grammarList).toString()
            
            val rec = Recognizer(voskModel, 16000.0f, grammarJson)
            voskSpeechService = SpeechService(rec, 16000.0f)
            voskSpeechService?.startListening(object : VoskRecognitionListener {
                override fun onPartialResult(hypothesis: String) {
                    val text = JSONObject(hypothesis).optString("partial")
                    if (text.isNotEmpty()) {
                        val currentTotal = if (voskFinalBuffer.isEmpty()) text else "${voskFinalBuffer} $text"
                        procesarTextoDictado(currentTotal, false, estudiantesList)
                    }
                }

                override fun onResult(hypothesis: String) {
                    val text = JSONObject(hypothesis).optString("text")
                    if (text.isNotEmpty()) {
                        if (voskFinalBuffer.isNotEmpty()) voskFinalBuffer.append(" ")
                        voskFinalBuffer.append(text)
                        procesarTextoDictado(voskFinalBuffer.toString(), true, estudiantesList)
                    }
                }

                override fun onFinalResult(hypothesis: String) {
                    val text = JSONObject(hypothesis).optString("text")
                    if (text.isNotEmpty()) {
                        if (voskFinalBuffer.isNotEmpty()) voskFinalBuffer.append(" ")
                        voskFinalBuffer.append(text)
                        procesarTextoDictado(voskFinalBuffer.toString(), true, estudiantesList)
                    }
                }

                override fun onError(exception: java.lang.Exception) {
                    callback.onError(exception.message ?: "Error desconocido en Vosk")
                }
                override fun onTimeout() {}
            })
            voskListeningActive = true
        } catch (e: Exception) {
            voskListeningActive = false
            stopDictation()
        }
    }

    private fun stopVoskDictation() {
        if (!voskListeningActive) {
            voskSpeechService = null
            return
        }
        voskListeningActive = false
        try {
            voskSpeechService?.stop()
        } catch (e: IllegalArgumentException) {
            Log.w("DictationManager", "Vosk: receiver ya liberado")
        } catch (e: Exception) {
            Log.e("DictationManager", "Error deteniendo Vosk: ${e.message}")
        }
        voskSpeechService = null
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
                if (isDictationMode && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH || error == 7)) {
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
                    procesarTextoDictado(matches[0], true, estudiantesList)
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
        val geminiApiKey = BuildConfig.GEMINI_API_KEY
        if (geminiApiKey.isEmpty()) {
            callback.onError("API Key de Gemini no encontrada. Por favor agrégala en local.properties.")
            return
        }

        if (generativeModel == null) {
            generativeModel = GenerativeModel(
                modelName = "gemini-flash-latest",
                apiKey = geminiApiKey
            )
        }

        // Método legacy
        Log.d("DictationManager", "Procesando audio localmente: ${audioFile.absolutePath}")
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
                val content = content {
                    text(systemPrompt)
                    blob("audio/mpeg", inputAudio)
                    text("Procesa este audio y devuelve las actualizaciones de notas.")
                }

                val response = generativeModel?.generateContent(content)
                val responseText = response?.text ?: ""
                
                val jsonMatch = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(responseText)
                val jsonString = jsonMatch?.value ?: responseText
                
                withContext(Dispatchers.Main) {
                    aplicarActualizacionesGemini(jsonString)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onError("Error Gemini: ${e.message}")
                }
            }
        }
    }

    private fun aplicarActualizacionesGemini(jsonString: String) {
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
            callback.onBulkGradesRecognized(listUpdates)
        } catch (e: Exception) {
            callback.onError("Error interpretando respuesta de Gemini")
        }
    }

    // --- PROCESAMIENTO TEXTO / EXPRESIONES REGULARES ---
    private fun procesarTextoDictado(texto: String, esFinal: Boolean, estudiantesList: List<Estudiante>) {
        if (texto.isEmpty()) return
        
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

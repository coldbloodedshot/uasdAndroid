package com.uasd.main

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.IOException

class AudioRecorder(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    var outputFile: File? = null
    private var isRecording = false

    fun startRecording(fileName: String): Boolean {
        outputFile = File(context.cacheDir, "$fileName.m4a")
        
        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(outputFile?.absolutePath)
            
            try {
                prepare()
                start()
                isRecording = true
                Log.d("AudioRecorder", "Recording started: ${outputFile?.absolutePath}")
            } catch (e: IOException) {
                Log.e("AudioRecorder", "prepare() failed", e)
                return false
            }
        }
        return true
    }

    fun stopRecording(): File? {
        if (!isRecording) return null
        
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioRecorder", "stop() failed", e)
        }
        
        mediaRecorder = null
        isRecording = false
        return outputFile
    }
}

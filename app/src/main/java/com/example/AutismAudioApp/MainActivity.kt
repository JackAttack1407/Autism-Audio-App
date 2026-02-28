package com.example.autismaudioapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.math.log10
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var meterText: TextView
    private var isRecording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        meterText = findViewById(R.id.meterText)

        // Request microphone permission
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1
            )
        } else {
            startAudio()
        }
    }

    private fun startAudio() {
        val sampleRate = 44100

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, // better mic capture
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        // Check if AudioRecord initialized correctly
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            meterText.text = "AudioRecord init failed!"
            return
        }

        isRecording = true
        audioRecord.startRecording()

        Thread {
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    // Print first 10 samples for debugging
                    val firstSamples = buffer.take(10).joinToString()
                    println("First 10 samples: $firstSamples")

                    // Compute RMS and dB
                    var sum = 0.0
                    for (i in 0 until read) {
                        sum += buffer[i] * buffer[i]
                    }
                    val rms = sqrt(sum / read)
                    val normalized = rms / 32768.0
                    val db = if (normalized > 0) 20 * log10(normalized) else -90.0

                    runOnUiThread {
                        meterText.text = "Volume: %.2f dB".format(db)
                    }
                }
            }
            audioRecord.stop()
            audioRecord.release()
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
    }
}


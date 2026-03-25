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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startAudio()
        } else {
            meterText.text = getString(R.string.audio_permission_denied)
        }
    }

    private fun startAudio() {
        // Check microphone permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            meterText.text = getString(R.string.audio_permission_denied)
            return
        }

        val sampleRate = 44100

        // Minimum buffer size
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, // Use raw mic input
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        // Ensure AudioRecord initialized correctly
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            meterText.text = getString(R.string.audio_init_failed)
            return
        }

        isRecording = true

        try {
            audioRecord.startRecording()
        } catch (e: SecurityException) {
            e.printStackTrace()
            meterText.text = getString(R.string.audio_permission_denied)
            return
        }

        Thread {
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord.read(buffer, 0, buffer.size)

                if (read > 0) {
                    // Compute RMS
                    val sum = buffer.take(read).sumOf { it.toDouble() * it }
                    val rms = sqrt(sum / read)

                    // Normalize and convert to dB
                    val normalized = rms / 32768.0
                    val db = if (normalized > 0) 20 * log10(normalized) else -90.0

                    runOnUiThread {
                        meterText.text = getString(R.string.volume_db, db)
                    }
                }
            }

            audioRecord.stop()
            audioRecord.release()
        }.start()
        //rms is the pitch of the audio wave, db is the human friendly scale
        //outputs the number based on what's in the size of the buffer (50ms)
    }
    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        //if not having audio input for a bit, will just stop the loop

    }
}
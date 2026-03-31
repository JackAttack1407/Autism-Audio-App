//WARNING, known bug causes loop to freeze if it hits absolute silence
// eg - mic muted, breaks the loops

package com.example.autismaudioapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.math.log10
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var meterText: TextView // DB output level box thing
    private lateinit var levelView: AudioLevelView // AudioLevelView reference for graph thing
    private lateinit var toggleButton: Button // Start/Stop button
    private lateinit var btnPlay: Button // Play button
    private lateinit var btnPause: Button // Pause button
    private lateinit var btnStop: Button // Stop button
    private var isRecording = false

    private var audioThread: Thread? = null // Thread for audio loop

    private var mediaPlayer: MediaPlayer? = null // MediaPlayer for audio playback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        meterText = findViewById(R.id.meterText)  // initialize text output of decibels
        levelView = findViewById(R.id.levelView)  // initialize AudioLevelView
        toggleButton = findViewById(R.id.btnToggle) // initialize Start/Stop button
        btnPlay = findViewById(R.id.btnPlay) // initialize Play button
        btnPause = findViewById(R.id.btnPause) // initialize Pause button
        btnStop = findViewById(R.id.btnStop) // initialize Stop button

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
        }

        // Set up toggle button
        toggleButton.setOnClickListener {
            if (isRecording) {
                stopAudio()
            } else {
                startAudio()
            }
        }

        // Initialize button text safely
        toggleButton.text = getString(R.string.start)

        // Initialize MediaPlayer
        mediaPlayer = MediaPlayer.create(this, R.raw.test) // usest the res/raw/test.mp3 currently

        // Play button
        btnPlay.setOnClickListener {
            mediaPlayer?.start()
        }

        // Pause button
        btnPause.setOnClickListener {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        }

        // Stop button
        btnStop.setOnClickListener {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(this, R.raw.test) // recreate so it can play again
        }                                                       // usest the res/raw/test.mp3 currently
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // Permission granted, optionally start audio automatically
        } else {
            meterText.text = getString(R.string.audio_permission_denied)
        }
    }

    private fun startAudio() {
        // Check microphone permission
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
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
        toggleButton.text = getString(R.string.stop)

        try {
            audioRecord.startRecording()
        } catch (e: SecurityException) {
            e.printStackTrace()
            meterText.text = getString(R.string.audio_permission_denied)
            return
        }

        audioThread = Thread {
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord.read(buffer, 0, buffer.size)

                if (read <= 0) {
                    Thread.sleep(10) // small pause to prevent buffer overrun / CPU hog
                    continue
                }

                // Compute RMS
                val sum = buffer.take(read).sumOf { it.toDouble() * it }
                val rms = sqrt(sum / read)

                // Normalize and convert to dB
                val normalized = rms / 32768.0
                val safeNormalized =
                    normalized.coerceAtLeast(1e-6) // avoid log(0) to avoid hitting -120db which bricks
                val db = 20 * log10(safeNormalized)

                // Generate multiple bars for AudioLevelView
                val numBars = 16
                val chunkSize = read / numBars
                val levels = FloatArray(numBars) { i ->
                    val start = i * chunkSize
                    val end = (i + 1) * chunkSize
                    if (start >= read) 0f
                    else {
                        val chunkSum = buffer.sliceArray(start until minOf(end, read))
                            .sumOf { it.toDouble() * it }
                        sqrt(chunkSum / (end - start)) / 32768f
                    }.toFloat()
                }

                runOnUiThread {
                    meterText.text = getString(R.string.volume_db, db)
                    levelView.setLevels(levels)  // <-- update multi-bar AudioLevelView
                }
            }

            audioRecord.stop()
            audioRecord.release()
        }.also { it.start() }

        //rms is the pitch of the audio wave, db is the human friendly scale
        //outputs the number based on what's in the size of the buffer (50ms)
    }

    private fun stopAudio() {
        isRecording = false
        toggleButton.text = getString(R.string.start)
        // Audio thread will exit naturally when it checks isRecording
        audioThread = null // allow garbage collection
    }
    // Stops the recording loop safely without blocking the UI; thread will exit on its own

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
        mediaPlayer?.release()
        // Ensures recording stops when the app is closed
    }
}
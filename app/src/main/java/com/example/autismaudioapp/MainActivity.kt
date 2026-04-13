package com.example.autismaudioapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.math.log10
import kotlin.math.sqrt
import java.io.File

class MainActivity : AppCompatActivity() {

    // =========================
    // UI ELEMENTS
    // =========================
    private lateinit var meterText: TextView
    private lateinit var levelView: AudioLevelView
    private lateinit var toggleButton: Button
    private lateinit var btnConfig: Button

    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnChoose: Button
    private lateinit var btnRemove: Button
    private lateinit var playlistView: ListView

    private lateinit var songSeekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView

    // =========================
    // AUDIO STATE
    // =========================
    private var isRecording = false
    private var audioThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    private var mediaPlayer: MediaPlayer? = null
    private val playlist = mutableListOf<File>()
    private var currentIndex = 0

    // popup control (prevents spam)
    private var warningShown = false
    private var lastWarningTime = 0L
    private val warningCooldownMs = 2500L

    // =========================
    // SHARED PREFERENCES (CONFIG SYSTEM)
    // =========================
    companion object {
        private const val PREFS_NAME = "app_config"
        private const val KEY_DB_THRESHOLD = "db_threshold"
        private const val DEFAULT_DB_THRESHOLD = 70f
    }

    private fun getPrefs() =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun getDbThreshold(): Float =
        getPrefs().getFloat(KEY_DB_THRESHOLD, DEFAULT_DB_THRESHOLD)

    private fun setDbThreshold(value: Float) {
        getPrefs().edit()
            .putFloat(KEY_DB_THRESHOLD, value)
            .apply()
    //Use the KTX extension function SharedPreferences.edit instead?
        //requires a newer kotlin version, which cannot currently be used (14/04/2026)
    }

    // =========================
    // AUDIO PICKER
    // =========================
    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { saveAudioFile(it) }
        }

    // =========================
    // ON CREATE
    // =========================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // bind UI
        meterText = findViewById(R.id.meterText)
        levelView = findViewById(R.id.levelView)
        toggleButton = findViewById(R.id.btnToggle)

        btnConfig = findViewById(R.id.btnConfig)

        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnChoose = findViewById(R.id.btnChoose)
        btnRemove = findViewById(R.id.btnRemove)
        playlistView = findViewById(R.id.playlistView)

        songSeekBar = findViewById(R.id.songSeekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)

        // reset UI
        songSeekBar.progress = 0

        // =========================
        // PERMISSION CHECK (FIXED LINT ISSUE)
        // =========================
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

        // start/stop mic monitoring
        toggleButton.setOnClickListener {
            if (isRecording) stopAudio() else startAudio()
        }

        // open config popup
        btnConfig.setOnClickListener { showConfigDialog() }

        loadPlaylist()

        btnPlay.setOnClickListener { togglePlayPause() }
        btnStop.setOnClickListener { stopCurrent() }
        btnNext.setOnClickListener { nextTrack() }
        btnPrev.setOnClickListener { prevTrack() }
        btnChoose.setOnClickListener { pickAudio.launch("audio/*") }

        btnRemove.setOnClickListener {
            if (playlist.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle("Remove Track")
                    .setMessage("Remove ${playlist[currentIndex].name}?")
                    .setPositiveButton("Confirm") { _, _ -> removeTrack() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        playlistView.setOnItemClickListener { _, _, position, _ ->
            currentIndex = position
            playCurrent()
        }
    }

    // =========================
    // CONFIG POPUP
    // =========================
    private fun showConfigDialog() {
        val input = EditText(this)

        // show current value
        input.setText(getDbThreshold().toString())

        input.inputType =
            android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL

        AlertDialog.Builder(this)
            .setTitle("Set dB Threshold")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                input.text.toString().toFloatOrNull()?.let {
                    setDbThreshold(it)
                    Toast.makeText(this, "Threshold set to $it dB", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================
    // AUDIO MONITORING START
    // =========================
    private fun startAudio() {

        // extra permission safety (prevents crash)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
            return
        }

        val sampleRate = 44100

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        // safe creation
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        val recorder = audioRecord ?: return

        // check initialization
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "Audio init failed", Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = true
        warningShown = false
        toggleButton.text = "Stop"

        recorder.startRecording()

        // background thread for audio analysis
        audioThread = Thread {

            val buffer = ShortArray(bufferSize)

            while (isRecording) {

                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                // RMS calculation
                val sum = buffer.take(read).sumOf { it.toDouble() * it }
                val rms = sqrt(sum / read)

                val rawDb = 20 * log10((rms / 32768.0).coerceAtLeast(1e-6))

                // convert to friendly scale (0–120)
                val db = (rawDb + 100).coerceIn(0.0, 120.0)

                runOnUiThread {

                    meterText.text = "dB: ${"%.1f".format(db)}"

                    val threshold = getDbThreshold()
                    val now = System.currentTimeMillis()

                    // trigger popup warning
                    if (db > threshold &&
                        !warningShown &&
                        now - lastWarningTime > warningCooldownMs
                    ) {
                        warningShown = true
                        lastWarningTime = now

                        AlertDialog.Builder(this)
                            .setTitle("⚠ Noise Warning")
                            .setMessage("Sound exceeded $threshold dB")
                            .setPositiveButton("OK") { dialog, _ ->
                                dialog.dismiss()
                                warningShown = false
                            }
                            .setCancelable(false)
                            .show()
                    }

                    // reset when safe again
                    if (db <= threshold) {
                        warningShown = false
                    }
                }
            }

            // cleanup
            try {
                recorder.stop()
                recorder.release()
            } catch (_: Exception) {}
        }

        audioThread?.start()
    }

    // =========================
    // STOP AUDIO MONITORING
    // =========================
    private fun stopAudio() {
        isRecording = false
        toggleButton.text = "Start"

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}

        audioRecord = null
    }

    // =========================
    // MEDIA CONTROLS (UNCHANGED)
    // =========================

    private fun removeTrack() {
        if (playlist.isEmpty()) return
        val file = playlist.removeAt(currentIndex)
        file.delete()
        loadPlaylist()
    }

    private fun togglePlayPause() {
        if (mediaPlayer == null) playCurrent()
        else if (mediaPlayer!!.isPlaying) mediaPlayer?.pause()
        else mediaPlayer?.start()
    }

    private fun playCurrent() {
        if (playlist.isEmpty()) return

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(playlist[currentIndex].absolutePath)
            prepare()
            start()
        }
    }

    private fun stopCurrent() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun nextTrack() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        playCurrent()
    }

    private fun prevTrack() {
        if (playlist.isEmpty()) return
        currentIndex = if (currentIndex == 0) playlist.size - 1 else currentIndex - 1
        playCurrent()
    }

    private fun loadPlaylist() {
        val folder = File(filesDir, "audio")
        if (!folder.exists()) folder.mkdirs()

        playlist.clear()
        playlist.addAll(folder.listFiles()?.toList() ?: emptyList())

        playlistView.adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, playlist.map { it.name })
    }

    private fun saveAudioFile(uri: Uri) {
        val folder = File(filesDir, "audio")
        if (!folder.exists()) folder.mkdirs()

        val file = File(folder, "track_${System.currentTimeMillis()}.mp3")

        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        loadPlaylist()
    }
}
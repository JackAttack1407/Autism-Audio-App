package com.example.autismaudioapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.math.log10
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    //UI
    private lateinit var meterText: TextView
    private lateinit var levelView: AudioLevelView
    private lateinit var toggleButton: Button

    private lateinit var btnPlay: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnChoose: Button
    private lateinit var btnRemove: Button
    private lateinit var btnConfig: Button

    private lateinit var playlistView: ListView
    private lateinit var songSeekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView

    //Record
    private var audioRecord: AudioRecord? = null
    @Volatile private var isRecording = false

    //Playback
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var isSeeking = false

    private val handler = Handler(Looper.getMainLooper())

    //Trigger
    private var lastTriggerTime = 0L
    private val triggerCooldown = 10_000L

    //Popup
    @Volatile private var isPopupShowing = false

    //Playlist
    private val playlist = mutableListOf<Uri>()
    private val playlistNames = mutableListOf<String>()
    private var currentIndex = 0

    //Prefs
    companion object {
        private const val PREFS = "audio_prefs"
        private const val KEY_THRESHOLD = "threshold"
    }

    private fun prefs() =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getThreshold(): Float =
        prefs().getFloat(KEY_THRESHOLD, 70f)

    //Audio picker
    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                try {
                    contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}

                playlist.add(it)
                playlistNames.add(it.lastPathSegment ?: "user_audio")

                saveUserAudio(it)
                refreshList()
            }
        }

    //On create
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        meterText = findViewById(R.id.meterText)
        levelView = findViewById(R.id.levelView)
        toggleButton = findViewById(R.id.btnToggle)

        btnPlay = findViewById(R.id.btnPlay)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnChoose = findViewById(R.id.btnChoose)
        btnRemove = findViewById(R.id.btnRemove)
        btnConfig = findViewById(R.id.btnConfig)

        playlistView = findViewById(R.id.playlistView)
        songSeekBar = findViewById(R.id.songSeekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)

        loadAssets()
        loadUserAudio()
        refreshList()

        //Start/Stop recording
        toggleButton.setOnClickListener {
            if (isRecording) stopAudio() else startAudio()
        }

        //Playback controls
        btnPlay.setOnClickListener { togglePlayPause() }
        btnNext.setOnClickListener { nextTrack() }
        btnPrev.setOnClickListener { prevTrack() }
        btnChoose.setOnClickListener { pickAudio.launch(arrayOf("audio/*")) }
        btnRemove.setOnClickListener { removeSelected() }
        btnConfig.setOnClickListener { showConfig() }

        playlistView.setOnItemClickListener { _, _, pos, _ ->
            currentIndex = pos
            playCurrent()
        }

        songSeekBar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {

            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaPlayer?.seekTo(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
            }
        })

        requestMicPermission()
    }

    //Config
    private fun showConfig() {
        val input = EditText(this)
        input.setText(getThreshold().toString())

        AlertDialog.Builder(this)
            .setTitle("Set Threshold")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                input.text.toString().toFloatOrNull()?.let {
                    prefs().edit().putFloat(KEY_THRESHOLD, it).apply()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    //Assets
    private fun loadAssets() {
        val files = assets.list("audio") ?: return
        for (file in files) {
            playlist.add(Uri.parse("file:///android_asset/audio/$file"))
            playlistNames.add(file)
        }
    }

    //Playlist
    private fun loadUserAudio() {
        val saved = prefs().getStringSet("user_audio", emptySet()) ?: return
        for (uriString in saved) {
            val uri = Uri.parse(uriString)
            playlist.add(uri)
            playlistNames.add(uri.lastPathSegment ?: "user_audio")
        }
    }

    private fun refreshList() {
        playlistView.adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, playlistNames)
    }

    //Playback
    private fun playCurrent() {
        if (playlist.isEmpty()) return

        mediaPlayer?.release()
        mediaPlayer = null

        val uri = playlist[currentIndex]
        val mp = MediaPlayer()

        try {
            if (uri.toString().startsWith("file:///android_asset")) {
                val path = uri.path!!.removePrefix("/android_asset/")
                val afd = assets.openFd(path)
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            } else {
                mp.setDataSource(this, uri)
            }

            mp.prepare()
            mp.setOnCompletionListener { nextTrack() }
            mp.start()

            mediaPlayer = mp
            isPlaying = true
            btnPlay.text = "Pause"

            songSeekBar.max = mp.duration
            tvTotalTime.text = format(mp.duration)

            updateSeekBar()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun togglePlayPause() {
        val mp = mediaPlayer ?: run {
            playCurrent()
            return
        }

        if (mp.isPlaying) {
            mp.pause()
            isPlaying = false
            btnPlay.text = "Play"
        } else {
            mp.start()
            isPlaying = true
            btnPlay.text = "Pause"
            updateSeekBar()
        }
    }

    //Playlist controls
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

    private fun removeSelected() {
        if (playlist.isEmpty()) return

        playlist.removeAt(currentIndex)
        playlistNames.removeAt(currentIndex)

        if (currentIndex >= playlist.size) currentIndex = 0

        refreshList()
    }

    //Seekbar
    private fun updateSeekBar() {
        handler.post(object : Runnable {
            override fun run() {
                val mp = mediaPlayer ?: return

                if (!isSeeking && mp.isPlaying) {
                    songSeekBar.progress = mp.currentPosition
                    tvCurrentTime.text = format(mp.currentPosition)
                }

                if (mp.isPlaying) handler.postDelayed(this, 500)
            }
        })
    }

    private fun format(ms: Int): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    //Save audio
    private fun saveUserAudio(uri: Uri) {
        val set = prefs()
            .getStringSet("user_audio", mutableSetOf())!!
            .toMutableSet()

        set.add(uri.toString())

        prefs().edit()
            .putStringSet("user_audio", set)
            .apply()
    }

    //Start recording
    private fun startAudio() {

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val sampleRate = 44100

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord = recorder
        isRecording = true

        runOnUiThread { toggleButton.text = "Stop" }

        Thread {

            val buffer = ShortArray(bufferSize)

            try {
                recorder.startRecording()

                while (isRecording) {

                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read <= 0) continue

                    val rms = sqrt(buffer.take(read).sumOf { it * it.toDouble() } / read)

                    val db = (20 * log10(rms / 32768.0 + 1e-6) + 100)
                        .coerceIn(0.0, 120.0)

                    val norm = (db / 120.0).toFloat()

                    runOnUiThread {
                        meterText.text = "dB: %.1f".format(db)
                        levelView.setLevels(FloatArray(16) { norm })

                        if (db > getThreshold() &&
                            System.currentTimeMillis() - lastTriggerTime > triggerCooldown
                        ) {
                            lastTriggerTime = System.currentTimeMillis()
                            showThresholdPopup(db)
                        }
                    }
                }

            } finally {

                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (_: Exception) {}

                try {
                    recorder.release()
                } catch (_: Exception) {}

                if (audioRecord === recorder) audioRecord = null
            }
        }.start()
    }

    //Stop recording
    private fun stopAudio() {
        isRecording = false
        runOnUiThread { toggleButton.text = "Start" }
    }

    //Popup
    private fun showThresholdPopup(db: Double) {

        if (isPopupShowing) return
        isPopupShowing = true

        AlertDialog.Builder(this)
            .setTitle("Warning")
            .setMessage(
                "Warning: maybe go somewhere quieter.\n" +
                        "It’s louder than your chosen DB value.\n\n" +
                        "Current level: %.1f dB\nThreshold: %.1f dB"
                            .format(db, getThreshold())
            )
            .setCancelable(false)
            .setPositiveButton("OK") { d, _ ->
                d.dismiss()
                isPopupShowing = false
            }
            .setOnDismissListener {
                isPopupShowing = false
            }
            .show()
    }

    //Permission
    private fun requestMicPermission() {
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
    }
}
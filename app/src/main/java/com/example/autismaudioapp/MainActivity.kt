package com.example.autismaudioapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.math.log10
import kotlin.math.sqrt
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var meterText: TextView
    private lateinit var levelView: AudioLevelView
    private lateinit var toggleButton: Button

    private lateinit var btnPlay: Button
    private lateinit var btnStop: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnChoose: Button
    private lateinit var playlistView: ListView

    private lateinit var songSeekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView

    private var isRecording = false
    private var audioThread: Thread? = null

    private var mediaPlayer: MediaPlayer? = null
    private val playlist = mutableListOf<File>()
    private var currentIndex = 0

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { saveAudioFile(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        meterText = findViewById(R.id.meterText)
        levelView = findViewById(R.id.levelView)
        toggleButton = findViewById(R.id.btnToggle)

        btnPlay = findViewById(R.id.btnPlay)
        btnStop = findViewById(R.id.btnStop)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnChoose = findViewById(R.id.btnChoose)
        playlistView = findViewById(R.id.playlistView)

        songSeekBar = findViewById(R.id.songSeekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)

        // ✅ Initialize SeekBar and timers to clean state
        songSeekBar.progress = 0
        tvCurrentTime.text = getString(R.string.current_time, 0, 0)
        tvTotalTime.text = getString(R.string.total_time, 0, 0)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1
            )
        }

        toggleButton.setOnClickListener { if (isRecording) stopAudio() else startAudio() }

        loadPlaylist()

        btnPlay.setOnClickListener { togglePlayPause() }
        btnStop.setOnClickListener { stopCurrent() }
        btnNext.setOnClickListener { nextTrack() }
        btnPrev.setOnClickListener { prevTrack() }
        btnChoose.setOnClickListener { pickAudio.launch("audio/*") }

        playlistView.setOnItemClickListener { _, _, position, _ ->
            currentIndex = position
            playCurrent()
        }

        songSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaPlayer?.seekTo(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun togglePlayPause() {
        if (playlist.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_audio_files), Toast.LENGTH_SHORT).show()
            return
        }

        when {
            mediaPlayer == null -> {
                playCurrent()
                btnPlay.text = getString(R.string.pause)
            }
            mediaPlayer!!.isPlaying -> {
                mediaPlayer?.pause()
                songSeekBar.removeCallbacks(updateSeekBarRunnable)
                btnPlay.text = getString(R.string.play)
            }
            else -> {
                mediaPlayer?.start()
                songSeekBar.post(updateSeekBarRunnable)
                btnPlay.text = getString(R.string.pause)
            }
        }
    }

    private fun playCurrent() {
        if (playlist.isEmpty()) return

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(playlist[currentIndex].absolutePath)
            prepare()
            start()
            setOnCompletionListener {
                mediaPlayer = null
                nextTrack()
            }
        }

        songSeekBar.max = mediaPlayer!!.duration
        songSeekBar.progress = 0
        tvCurrentTime.text = getString(R.string.current_time, 0, 0)
        tvTotalTime.text = formatTime(mediaPlayer!!.duration)
        songSeekBar.post(updateSeekBarRunnable)
        btnPlay.text = getString(R.string.pause)
    }

    private fun stopCurrent() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null

        songSeekBar.removeCallbacks(updateSeekBarRunnable)
        songSeekBar.progress = 0
        tvCurrentTime.text = getString(R.string.current_time, 0, 0)
        btnPlay.text = getString(R.string.play)
    }

    private fun nextTrack() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        playCurrent()
    }

    private fun prevTrack() {
        if (playlist.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
        playCurrent()
    }

    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let {
                songSeekBar.progress = it.currentPosition
                tvCurrentTime.text = formatTime(it.currentPosition)
                songSeekBar.postDelayed(this, 500)
            }
        }
    }

    private fun loadPlaylist() {
        val folder = File(filesDir, "audio")
        if (!folder.exists()) folder.mkdirs()

        playlist.clear()
        playlist.addAll(folder.listFiles()?.filter { it.extension.lowercase() in listOf("mp3", "wav") } ?: emptyList())

        val names = playlist.map { it.name }
        playlistView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
    }

    private fun saveAudioFile(uri: Uri) {
        val folder = File(filesDir, "audio")
        if (!folder.exists()) folder.mkdirs()

        val name = getFileName(uri) ?: "track_${System.currentTimeMillis()}.mp3"
        val file = File(folder, name)

        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        Toast.makeText(this, getString(R.string.added_file, name), Toast.LENGTH_SHORT).show()
        loadPlaylist()
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = it.getString(index)
            }
        }
        return name
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return getString(R.string.current_time, minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }

    private fun startAudio() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            meterText.text = getString(R.string.audio_permission_denied)
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                1
            )
            return
        }

        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            meterText.text = getString(R.string.permission_error, e.message)
            return
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            meterText.text = getString(R.string.audio_init_failed)
            return
        }

        isRecording = true
        toggleButton.text = getString(R.string.stop)
        audioRecord.startRecording()

        audioThread = Thread {
            val buffer = ShortArray(bufferSize)
            while (isRecording) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                val sum = buffer.take(read).sumOf { it.toDouble() * it }
                val rms = sqrt(sum / read)
                val db = 20 * log10((rms / 32768.0).coerceAtLeast(1e-6))

                runOnUiThread {
                    meterText.text = getString(R.string.volume_db, db)
                }
            }
            audioRecord.stop()
            audioRecord.release()
        }.also { it.start() }
    }

    private fun stopAudio() {
        isRecording = false
        toggleButton.text = getString(R.string.start)
    }
}
//currently known issue where the pause button cannot unpause songs
//also ui is weirdly high up

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
    private lateinit var btnPause: Button
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

    // File picker for selecting audio
    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { saveAudioFile(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        meterText = findViewById(R.id.meterText)
        levelView = findViewById(R.id.levelView)
        toggleButton = findViewById(R.id.btnToggle)

        btnPlay = findViewById(R.id.btnPlay)
        btnPause = findViewById(R.id.btnPause)
        btnStop = findViewById(R.id.btnStop)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnChoose = findViewById(R.id.btnChoose)
        playlistView = findViewById(R.id.playlistView)

        songSeekBar = findViewById(R.id.songSeekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)

        // Request microphone permission
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
        toggleButton.text = getString(R.string.start)

        // Initialize playlist and MediaPlayer controls
        loadPlaylist()
        btnPlay.setOnClickListener { playCurrent() }
        btnPause.setOnClickListener { pauseCurrent() }
        btnStop.setOnClickListener { stopCurrent() }
        btnNext.setOnClickListener { nextTrack() }
        btnPrev.setOnClickListener { prevTrack() }
        btnChoose.setOnClickListener { pickAudio.launch("audio/*") }

        playlistView.setOnItemClickListener { _, _, position, _ ->
            currentIndex = position
            playCurrent()
        }

        playlistView.setOnItemLongClickListener { _, _, _, _ ->
            pickAudio.launch("audio/*")
            true
        }

        // SeekBar listener
        songSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) mediaPlayer?.seekTo(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /** Save audio from Uri to internal folder with proper filename */
    private fun saveAudioFile(uri: Uri) {
        val audioFolder = File(filesDir, "audio")
        if (!audioFolder.exists()) audioFolder.mkdirs()

        val fileName = getFileName(uri) ?: "track_${System.currentTimeMillis()}.mp3"
        val destFile = File(audioFolder, fileName)

        try {
            contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(this, "Added $fileName", Toast.LENGTH_SHORT).show()
            loadPlaylist()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to add $fileName", Toast.LENGTH_SHORT).show()
        }
    }

    /** Helper to get filename from Uri */
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

    /** Runnable to update seek bar while playing */
    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { mp ->
                songSeekBar.progress = mp.currentPosition
                tvCurrentTime.text = formatTime(mp.currentPosition)
                songSeekBar.postDelayed(this, 500)
            }
        }
    }

    /** Load playlist from internal audio folder */
    private fun loadPlaylist() {
        val audioFolder = File(filesDir, "audio")
        if (!audioFolder.exists()) audioFolder.mkdirs()

        playlist.clear()
        playlist.addAll(
            audioFolder.listFiles()?.filter { it.extension.lowercase() in listOf("mp3", "wav") }
                ?: emptyList()
        )

        val names = playlist.map { it.name }
        playlistView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
    }

    /** Play current track with auto-next */
    private fun playCurrent() {
        if (playlist.isEmpty()) {
            Toast.makeText(this, "No audio files found", Toast.LENGTH_SHORT).show()
            return
        }

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(playlist[currentIndex].absolutePath)
            prepare()
            start()

            // Autoplay next track when current finishes
            setOnCompletionListener { nextTrack() }
        }

        songSeekBar.max = mediaPlayer!!.duration
        tvTotalTime.text = formatTime(mediaPlayer!!.duration)
        songSeekBar.post(updateSeekBarRunnable)
    }

    /** Pause current track */
    private fun pauseCurrent() {
        mediaPlayer?.pause()
        songSeekBar.removeCallbacks(updateSeekBarRunnable)
    }

    /** Stop current track */
    private fun stopCurrent() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        songSeekBar.removeCallbacks(updateSeekBarRunnable)
        songSeekBar.progress = 0
        tvCurrentTime.text = "0:00" // hard coded to start at 0:00 every time, and it won't let me use @strings/current_time
                                    // for some reason I cant figure out yet
    }

    /** Next track */
    private fun nextTrack() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        playCurrent()
    }

    /** Previous track */
    private fun prevTrack() {
        if (playlist.isEmpty()) return
        currentIndex = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
        playCurrent()
    }

    /** Start microphone recording */
    private fun startAudio() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            meterText.text = getString(R.string.audio_permission_denied)
            return
        }

        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

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
                if (read <= 0) { Thread.sleep(10); continue }

                val sum = buffer.take(read).sumOf { it.toDouble() * it }
                val rms = sqrt(sum / read)
                val normalized = rms / 32768.0
                val safeNormalized = normalized.coerceAtLeast(1e-6)
                val db = 20 * log10(safeNormalized)

                val numBars = 16
                val chunkSize = read / numBars
                val levels = FloatArray(numBars) { i ->
                    val start = i * chunkSize
                    val end = (i + 1) * chunkSize
                    if (start >= read) 0f
                    else {
                        val chunkSum = buffer.sliceArray(start until minOf(end, read)).sumOf { it.toDouble() * it }
                        sqrt(chunkSum / (end - start)) / 32768f
                    }.toFloat()
                }

                runOnUiThread {
                    meterText.text = getString(R.string.volume_db, db)
                    levelView.setLevels(levels)
                }
            }
            audioRecord.stop()
            audioRecord.release()
        }.also { it.start() }
    }

    /** Stop microphone recording */
    private fun stopAudio() {
        isRecording = false
        toggleButton.text = getString(R.string.start)
        audioThread = null
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        } // empty if because if true then nothing needs to happen
        else {
            meterText.text = getString(R.string.audio_permission_denied)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
        mediaPlayer?.release()
    }
}
package com.example.autismaudioapp
// to add the logo, go to app/src/main/res/mipmap
//currently has test.png (big T)

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
import androidx.core.content.ContextCompat
import kotlin.math.log10
import kotlin.math.sqrt
import java.io.File

class MainActivity : AppCompatActivity() {

    // UI references
    private lateinit var meterText: TextView
    private lateinit var levelView: AudioLevelView
    private lateinit var toggleButton: Button
    private lateinit var btnConfig: Button

    // Settings controls
    private lateinit var switchSafeToggle: Switch
    private lateinit var safeList: ListView
    private lateinit var btnSensitivity: Button
    private lateinit var btnSafe: Button
    private lateinit var btnBack: Button
    private lateinit var safeText: TextView

    // playback controls
    private lateinit var btnPlay: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnChoose: Button
    private lateinit var btnRemove: Button
    private lateinit var playlistView: ListView
    private var boolSafe = false

    // seek bar UI
    private lateinit var songSeekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView

    // mic recording state
    private var isRecording = false
    private var audioThread: Thread? = null
    private var audioRecord: AudioRecord? = null

    // media player state
    private var mediaPlayer: MediaPlayer? = null
    private val playlist = mutableListOf<File>()
    private var currentIndex = 0
    private var safeIndex = 0

    // warning system (prevents repeated popups)
    private var warningShown = false
    private var lastWarningTime = 0L
    private val warningCooldownMs = 2500L

    // shared preferences keys (settings storage)
    companion object {
        private const val PREFS_NAME = "app_config"
        private const val KEY_DB_THRESHOLD = "db_threshold"
        private const val DEFAULT_DB_THRESHOLD = 70f
    }

    // get shared preferences instance
    private fun getPrefs() =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    // read saved noise threshold
    private fun getDbThreshold(): Float =
        getPrefs().getFloat(KEY_DB_THRESHOLD, DEFAULT_DB_THRESHOLD)

    // save noise threshold
    private fun setDbThreshold(value: Float) {
        getPrefs().edit()
            .putFloat(KEY_DB_THRESHOLD, value)
            .apply()
        //Use the KTX extension function SharedPreferences.edit instead?
        // requires a newer kotlin version, which cannot currently be used (14/04/2026)
    }

    // file picker for audio import
    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { saveAudioFile(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // bind UI elements - Made into a function for layout navigation
        bindUI()
    }

    private fun bindUI(){
        meterText = findViewById(R.id.meterText)
        levelView = findViewById(R.id.levelView)
        toggleButton = findViewById(R.id.btnToggle)

        btnConfig = findViewById(R.id.btnConfig)

        btnPlay = findViewById(R.id.btnPlay)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnChoose = findViewById(R.id.btnChoose)
        btnRemove = findViewById(R.id.btnRemove)
        playlistView = findViewById(R.id.playlistView)

        songSeekBar = findViewById(R.id.songSeekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)

        // reset seekbar on launch
        songSeekBar.progress = 0

        // request microphone permission if needed
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

        // toggle mic monitoring
        toggleButton.setOnClickListener {
            if (isRecording) stopAudio() else startAudio()
        }

        // SHOULD HAVE - mini pop up saying "Cannot configure while listening"
        // open settings, if not currently recording
        btnConfig.setOnClickListener { if(!isRecording) {openSettings()} }

        // load saved audio files into list
        loadPlaylist()

        // playback controls
        btnPlay.setOnClickListener { togglePlayPause() }
        btnNext.setOnClickListener { nextTrack() }
        btnPrev.setOnClickListener { prevTrack() }

        // file management
        btnChoose.setOnClickListener { pickAudio.launch("audio/*") }

        btnRemove.setOnClickListener {
            if (playlist.isNotEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.remove_title))
                    .setMessage(getString(R.string.remove_message, playlist[currentIndex].name))
                    .setPositiveButton(getString(R.string.confirm)) { _, _ -> removeTrack() }
                    .setNegativeButton(getString(R.string.cancel), null)
                    .show()
            }
        }

        // tap playlist item to play & show currently selected
        playlistView.setOnItemClickListener { _, _, position, _ ->
            val previous = currentIndex
            currentIndex = position

            // get position of currently selected
            val firstVisible = playlistView.firstVisiblePosition
            val childIndex = position - firstVisible
            val clickedView = playlistView.getChildAt(childIndex)

            // Darken background of currently selected
            clickedView?.setBackgroundColor(ContextCompat.getColor(this, R.color.selected_color))

            // Reset background to unselected when new item selected
            if (previous >= 0 && previous != currentIndex) {
                val prevChild = playlistView.getChildAt(previous - firstVisible)
                prevChild?.setBackgroundColor(ContextCompat.getColor(this, R.color.card_white))
            }

            playCurrent()
        }
    }
    private fun openSettings(){
        setContentView(R.layout.calibration_page)
        safeText = findViewById(R.id.safeText)
        safeText.text = getString(R.string.safe_text, playlist[safeIndex].name)

        btnSensitivity = findViewById(R.id.btnSensitivity)
        btnSensitivity.setOnClickListener {showConfigDialog()}

        // Show playlist
        btnSafe = findViewById(R.id.btnSafe)
        btnSafe.setOnClickListener { loadSafePlaylist() }

        // When item selected, hide list and give user confirmation
        safeList = findViewById(R.id.safeList)
        safeList.setOnItemClickListener { _, _, position, _ ->
            safeIndex = position
            safeText.text = getString(R.string.safe_text, playlist[safeIndex].name)
            safeList.visibility = android.view.View.INVISIBLE
        }

        switchSafeToggle = findViewById(R.id.switchSafeToggle)
        switchSafeToggle.isChecked = boolSafe
        switchSafeToggle.setOnCheckedChangeListener { _, isChecked ->
            boolSafe = if (isChecked) {
                // change bool to true
                true
            } else {
                // change bool to false
                false
            }
        }

        btnBack = findViewById(R.id.btnBack)
        btnBack.setOnClickListener {closeSettings()}
    }
    private fun closeSettings(){
        // Swap back to main layout
        setContentView(R.layout.activity_main)
        // re-bind UI
        bindUI()
    }
    // show threshold config popup
    private fun showConfigDialog() {
        val input = EditText(this)
        input.setText(getDbThreshold().toString())

        input.inputType =
            android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.set_threshold))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                input.text.toString().toFloatOrNull()?.let {
                    setDbThreshold(it)
                    Toast.makeText(
                        this,
                        getString(R.string.threshold_set, it),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // start microphone monitoring
    private fun startAudio() {

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, getString(R.string.mic_denied), Toast.LENGTH_SHORT).show()
            return
        }

        val sampleRate = 44100

        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        val recorder = audioRecord ?: return

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, getString(R.string.audio_init_failed), Toast.LENGTH_SHORT).show()
            return
        }

        isRecording = true
        warningShown = false
        toggleButton.text = getString(R.string.stop)

        recorder.startRecording()

        audioThread = Thread {

            val buffer = ShortArray(bufferSize)

            while (isRecording) {

                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                val sum = buffer.take(read).sumOf { it.toDouble() * it }
                val rms = sqrt(sum / read)

                val rawDb = 20 * log10((rms / 32768.0).coerceAtLeast(1e-6))
                val db = (rawDb + 100).coerceIn(0.0, 120.0)

                runOnUiThread {

                    meterText.text = getString(R.string.volume_db_value, db)

                    val normalized = (db / 120.0).toFloat().coerceIn(0f, 1f)

                    // 🚨 noise gate (key fix)
                    val isSilent = db < 2.0  // tweak 1–5 depending on mic noise

                    val time = System.currentTimeMillis() / 220.0

                    val levels = FloatArray(16) { i ->

                        if (isSilent) {
                            0f // freeze completely when silent
                        } else {
                            val phase = i * 0.6

                            val wave =
                                (kotlin.math.sin(time + phase) * 0.6 +
                                        kotlin.math.sin(time * 0.8 + phase * 1.3) * 0.3 +
                                        kotlin.math.sin(time * 1.6 + phase * 0.7) * 0.1).toFloat()

                            val shaped = ((wave + 1f) / 2f).coerceIn(0f, 1f)

                            (shaped * normalized).coerceIn(0f, 1f)
                        }
                    }

                    levelView.setLevels(levels)

                    levelView.setLevels(levels)

                    val threshold = getDbThreshold()
                    val now = System.currentTimeMillis()

                    if (db > threshold &&
                        !warningShown &&
                        now - lastWarningTime > warningCooldownMs
                    ) {
                        warningShown = true
                        lastWarningTime = now

                        if(boolSafe && (mediaPlayer?.isPlaying == false || mediaPlayer == null))
                        {
                            // play safe audio
                            if (!playlist.isEmpty()){
                                mediaPlayer?.release()

                                mediaPlayer = MediaPlayer().apply {
                                    setDataSource(playlist[safeIndex].absolutePath)
                                    prepare()
                                    start()
                                }
                            }

                            AlertDialog.Builder(this)
                                .setTitle(getString(R.string.warning_title))
                                .setMessage("Your safe sound is now playing.")
                                .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                                    dialog.dismiss()
                                    warningShown = false
                                }
                                .setCancelable(false)
                                .show()
                        }
                        else
                        {
                            // Dialog reappears too quick and ngl is very stressful
                            AlertDialog.Builder(this)
                                .setTitle(getString(R.string.warning_title))
                                .setMessage(getString(R.string.warning_message, threshold))
                                .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                                    dialog.dismiss()
                                    warningShown = false
                                }
                                .setCancelable(false)
                                .show()
                        }
                    }

                    if (db <= threshold) warningShown = false
                }
            }

            try {
                recorder.stop()
                recorder.release()
            } catch (_: Exception) {}
        }

        audioThread?.start()
    }

    // stop microphone monitoring
    private fun stopAudio() {
        isRecording = false
        toggleButton.text = getString(R.string.start)

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}

        audioRecord = null
    }

    // remove selected track
    private fun removeTrack() {
        if (playlist.isEmpty()) return
        val file = playlist.removeAt(currentIndex)
        file.delete()
        loadPlaylist()
    }

    // play or pause audio
    private fun togglePlayPause() {
        if (mediaPlayer == null) playCurrent()
        else if (mediaPlayer!!.isPlaying) mediaPlayer?.pause()
        else mediaPlayer?.start()
    }

    // play selected track
    private fun playCurrent() {
        if (playlist.isEmpty()) return

        mediaPlayer?.release()

        mediaPlayer = MediaPlayer().apply {
            setDataSource(playlist[currentIndex].absolutePath)
            prepare()
            start()
        }
    }

    // next track in playlist
    private fun nextTrack() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        playCurrent()
    }

    // previous track in playlist
    private fun prevTrack() {
        if (playlist.isEmpty()) return
        currentIndex = if (currentIndex == 0) playlist.size - 1 else currentIndex - 1
        playCurrent()
    }

    // load saved audio files
    private fun loadPlaylist() {
        val folder = File(filesDir, "audio")
        if (!folder.exists()) folder.mkdirs()

        playlist.clear()
        playlist.addAll(folder.listFiles()?.toList() ?: emptyList())

        playlistView.adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, playlist.map { it.name })
    }
    private fun loadSafePlaylist() {
        safeList.visibility = android.view.View.VISIBLE

        val folder = File(filesDir, "audio")
        if (!folder.exists()) folder.mkdirs()

        playlist.clear()
        playlist.addAll(folder.listFiles()?.toList() ?: emptyList())

        safeList.adapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, playlist.map { it.name })
    }

    // save imported audio file
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
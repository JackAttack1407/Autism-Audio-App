import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import kotlin.math.log10

class `MainActivity.kt` : AppCompatActivity() {

    private lateinit var meterText: TextView
    private var dispatcher: AudioDispatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        meterText = findViewById(R.id.meterText)

        // Request mic permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {

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
        val bufferSize = 1024

        // Create dispatcher using AudioRecord internally
        dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(
            sampleRate,
            bufferSize,
            0
        )

        val processor = object : AudioProcessor {
            override fun process(audioEvent: AudioEvent): Boolean {

                val rms = audioEvent.rms
                val db = 20 * log10(rms.toDouble())

                runOnUiThread {
                    meterText.text = "Volume: %.2f dB".format(db)
                }

                return true
            }

            override fun processingFinished() {}
        }

        dispatcher?.addAudioProcessor(processor)

        Thread(dispatcher, "Audio Dispatcher").start()
    }
}

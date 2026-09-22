package com.belawal.voicechanger

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val sampleRate = 44100
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var mediaPlayer: MediaPlayer? = null

    private lateinit var voiceDir: File
    private lateinit var rawPcmFile: File
    private lateinit var originalWavFile: File
    private lateinit var convertedWavFile: File

    private lateinit var statusText: TextView
    private lateinit var recordButton: Button
    private lateinit var convertButton: Button
    private lateinit var playButton: Button
    private lateinit var shareButton: Button

    private val requestPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording() else {
            Toast.makeText(this, "Mic permission ছাড়া record করা যাবে না", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        voiceDir = File(filesDir, "voice").apply { mkdirs() }
        rawPcmFile = File(voiceDir, "raw.pcm")
        originalWavFile = File(voiceDir, "original.wav")
        convertedWavFile = File(voiceDir, "converted.wav")

        statusText = findViewById(R.id.statusText)
        recordButton = findViewById(R.id.recordButton)
        convertButton = findViewById(R.id.convertButton)
        playButton = findViewById(R.id.playButton)
        shareButton = findViewById(R.id.shareButton)

        recordButton.setOnClickListener { onRecordClicked() }
        convertButton.setOnClickListener { onConvertClicked() }
        playButton.setOnClickListener { onPlayClicked() }
        shareButton.setOnClickListener { onShareClicked() }
    }

    // ---------- Recording ----------

    private fun onRecordClicked() {
        if (isRecording) {
            stopRecording()
            return
        }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startRecording() else requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    @Suppress("MissingPermission")
    private fun startRecording() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = max(minBuf, sampleRate) // at least ~1s worth for safety
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "Mic শুরু করা যায়নি", Toast.LENGTH_LONG).show()
            return
        }

        isRecording = true
        recordButton.text = "⏹️ Record বন্ধ করুন"
        convertButton.isEnabled = false
        playButton.isEnabled = false
        shareButton.isEnabled = false
        statusText.text = "Recording চলছে..."

        audioRecord?.startRecording()

        thread {
            FileOutputStream(rawPcmFile).use { out ->
                val buffer = ByteArray(bufSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) out.write(buffer, 0, read)
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        recordButton.text = "🎙️ Record শুরু করুন"
        statusText.text = "Record শেষ। এবার Convert করুন।"

        // small delay isn't needed; the writer thread finishes its last write quickly,
        // but to be safe we wrap on a short background delay before touching the file.
        thread {
            Thread.sleep(150)
            WavUtils.pcmFileToWav(rawPcmFile, originalWavFile, sampleRate)
            runOnUiThread {
                convertButton.isEnabled = true
            }
        }
    }

    // ---------- Conversion ----------

    private fun onConvertClicked() {
        statusText.text = "Voice convert হচ্ছে..."
        convertButton.isEnabled = false

        thread {
            try {
                val wav = WavUtils.readWav(originalWavFile)
                val shifted = PitchShifter.shiftPitch(wav.samples, PitchShifter.MALE_TO_FEMALE_PITCH)
                WavUtils.writeWav(convertedWavFile, shifted, wav.sampleRate)
                runOnUiThread {
                    statusText.text = "Convert সম্পন্ন ✔️"
                    playButton.isEnabled = true
                    shareButton.isEnabled = true
                    convertButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Convert ব্যর্থ হয়েছে"
                    convertButton.isEnabled = true
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---------- Playback ----------

    private fun onPlayClicked() {
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(convertedWavFile.absolutePath)
            prepare()
            start()
        }
    }

    // ---------- Share to WhatsApp ----------

    private fun onShareClicked() {
        val uri: Uri = FileProvider.getUriForFile(
            this, "$packageName.fileprovider", convertedWavFile
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.whatsapp")
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // WhatsApp not found under that package name (e.g. Business app) -> fall back to chooser
            intent.setPackage(null)
            startActivity(android.content.Intent.createChooser(intent, "Voice message পাঠান"))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        audioRecord?.release()
    }

    private fun max(a: Int, b: Int) = if (a > b) a else b
}

package win.moez.zplayer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.arthenica.ffmpegkit.FFmpegKit
import com.google.cloud.speech.v1.*
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import java.io.*
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {

    private lateinit var pickVideoLauncher: ActivityResultLauncher<String>
    internal var videoUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                videoUri = it
            }
        }
        setContent {
            VideoPlayerApp(this, pickVideoLauncher)
        }

        requestPermissions()
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 0)
        }
    }
}

@Composable
fun VideoPlayerApp(mainActivity: MainActivity, pickVideoLauncher: ActivityResultLauncher<String>) {
    val context = mainActivity.baseContext
    val player = remember { ExoPlayer.Builder(context).build() }
    var subtitleProgress by remember { mutableStateOf("未开始") }
    var subtitlePath by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Button(onClick = { pickVideoLauncher.launch("video/*") }) {
            Text("选择本地视频")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = { mainActivity.videoUri = Uri.parse("https://stream7.iqilu.com/10339/upload_transcode/202002/09/20200209105011F0zPoYzHry.mp4") }) {
            Text("播放在线视频")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "字幕生成进度: $subtitleProgress", modifier = Modifier.padding(8.dp))

        mainActivity.videoUri?.let { uri ->
            LaunchedEffect(uri) {
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                player.play()
                extractAndProcessAudio(context, uri.toString()) { path, progress ->
                    subtitleProgress = progress
                    subtitlePath = path
                    path?.let { loadSubtitle(player, it) }
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                    }
                }
            )
        }
    }
}

fun getAppSpecificFile(context: Context, fileName: String): File {
    val appSpecificDir = context.getExternalFilesDir(null)
    return File(appSpecificDir, fileName)
}

fun extractAndProcessAudio(
    context: Context,
    videoPath: String,
    onProgress: (String?, String) -> Unit
) {
    val outputFile = getAppSpecificFile(context, "output.wav")

    GlobalScope.launch(Dispatchers.IO) {
        try {
            onProgress(null, "提取音频中...")
            val command = "-i $videoPath -vn -acodec pcm_s16le -ar 16000 -ac 1 $outputFile"
            FFmpegKit.execute(command)

            onProgress(null, "识别字幕中...")
            val transcriptFile = recognizeSpeech(outputFile)

            onProgress(null, "翻译字幕中...")
            val subtitlePath = translateSubtitleFile(context, transcriptFile).toString()
            onProgress(subtitlePath, "字幕已生成！")
        } catch (e: Exception) {
            Log.e("SubtitleError", "字幕处理失败", e)
            onProgress(null, "字幕生成失败")
        }
    }
}

fun recognizeSpeech(audioFile: File): File {
    val speechClient = SpeechClient.create()
    val srtFile = File(audioFile.parent, "subtitles.srt")
    val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(srtFile), StandardCharsets.UTF_8))

    val response = speechClient.recognize(
        RecognizeRequest.newBuilder()
            .setConfig(
                RecognitionConfig.newBuilder()
                    .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                    .setSampleRateHertz(16000)
                    .setLanguageCode("ja-JP") // 主要语言：日文
                    .addAllAlternativeLanguageCodes(listOf("en-US", "zh-CN")) // 备用语言：英文和中文
                    .setEnableWordTimeOffsets(true)
                    .build()
            )
            .setAudio(
                RecognitionAudio.newBuilder()
                    .setContent(ByteString.readFrom(FileInputStream(audioFile)))
                    .build()
            )
            .build()
    )

    var index = 1
    for (result in response.resultsList) {
        val alternative = result.alternativesList[0]
        val startTime = result.alternativesList[0].wordsList.first().startTime
        val endTime = result.alternativesList[0].wordsList.last().endTime

        writer.write("$index\n")
        writer.write(formatTime(startTime) + " --> " + formatTime(endTime) + "\n")
        writer.write(alternative.transcript + "\n\n")
        index++
    }
    writer.close()
    return srtFile
}

fun formatTime(duration: com.google.protobuf.Duration): String {
    val millis = duration.seconds * 1000 + duration.nanos / 1000000
    return "%02d:%02d:%02d,%03d".format(millis / 3600000, millis / 60000 % 60, millis / 1000 % 60, millis % 1000)
}

fun translateSubtitleFile(context: Context, srtFile: File): File {
    val translatedFile = getAppSpecificFile(context, "translated.srt")
    val translatedWriter = BufferedWriter(FileWriter(translatedFile))

    srtFile.forEachLine { line ->
        if (line.matches(Regex("\\d+")) || line.contains("-->")) {
            translatedWriter.write(line + "\n")
        } else {
            translatedWriter.write(line + "\n")
            translatedWriter.write(translateText(line) + "\n\n")
        }
    }
    translatedWriter.close()
    return translatedFile
}

fun translateText(text: String): String {
    val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh&dt=t&q=${text}"
    val request = Request.Builder().url(url).build()
    return OkHttpClient().newCall(request).execute().body!!.string().let { response ->
        JSONArray(response).getJSONArray(0).getJSONArray(0).getString(0)
    }
}

fun loadSubtitle(player: ExoPlayer, subtitlePath: String) {
    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitlePath))
        .setMimeType("application/x-subrip")
        .build()
    player.setMediaItem(
        player.currentMediaItem!!.buildUpon().setSubtitleConfigurations(listOf(subtitleConfig)).build()
    )
    player.prepare()
}

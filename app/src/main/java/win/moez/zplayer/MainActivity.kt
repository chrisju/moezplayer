package win.moez.zplayer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VideoPlayerApp()
        }

        // Request permissions
        requestPermissions()
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
//            Manifest.permission.MANAGE_EXTERNAL_STORAGE
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 0)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Handle the permissions request response
    }
}

@Composable
fun VideoPlayerApp() {
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    val context = LocalContext.current
    val player = remember { ExoPlayer.Builder(context).build() }
    var subtitleProgress by remember { mutableStateOf("未开始") }
    var subtitlePath by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        // 选择本地视频
        Button(onClick = { videoUri = selectVideo(context) }) {
            Text("选择本地视频")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 播放在线视频
        Button(onClick = { videoUri = Uri.parse("https://stream7.iqilu.com/10339/upload_transcode/202002/09/20200209105011F0zPoYzHry.mp4") }) {
            Text("播放在线视频")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 状态栏
        Text(text = "字幕生成进度: $subtitleProgress", modifier = Modifier.padding(8.dp))

        // ExoPlayer 播放器
        videoUri?.let { uri ->
            LaunchedEffect(uri) {
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                player.play()
                // 启动后台任务
                extractAndProcessAudio(context, uri.toString()) { path, progress ->
                    subtitleProgress = progress
                    subtitlePath = path
                }
            }

            AndroidView(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                factory = { ctx ->
                    PlayerView(ctx).apply {
//                        playerView = this // 这里改为 playerView
                        this.player = player
                    }
                }
            )
        }
    }
}

fun getAppSpecificFile(context: android.content.Context, fileName: String): File {
    // 获取应用专属外部存储目录
    val appSpecificDir = context.getExternalFilesDir(null)
    // 创建文件路径
    return File(appSpecificDir, fileName)
}

// 选择本地视频（未实现文件选择器，可扩展）
private fun selectVideo(context: android.content.Context): Uri? {
    val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "video/*" }
    context.startActivity(Intent.createChooser(intent, "选择视频文件"))
    return null
}

// 提取音频 & 识别字幕
fun extractAndProcessAudio(
    context: android.content.Context,
    videoPath: String,
    onProgress: (String?, String) -> Unit
) {
    val outputFile = getAppSpecificFile(context, "output.wav")

    GlobalScope.launch(Dispatchers.IO) {
        try {
            // 提取音频
            onProgress(null, "提取音频中...")
            val command = "-i $videoPath -vn -acodec pcm_s16le -ar 16000 -ac 1 $outputFile"
            FFmpegKit.execute(command)

            // 语音识别
            onProgress(null, "识别字幕中...")
            val transcript = recognizeSpeech(outputFile)

            // 翻译字幕
            onProgress(null, "翻译字幕中...")
            val translated = translateText(transcript)

            // 生成双语字幕
            val subtitlePath = generateSubtitleFile(context, transcript, translated).toString()
            onProgress(subtitlePath, "字幕已生成！")
        } catch (e: Exception) {
            Log.e("SubtitleError", "字幕处理失败", e)
            onProgress(null, "字幕生成失败")
        }
    }
}

// Google Speech-to-Text 识别
fun recognizeSpeech(audioFile: File): String {
    val speechClient = SpeechClient.create()
    val response = speechClient.recognize(
        RecognizeRequest.newBuilder()
            .setConfig(
                RecognitionConfig.newBuilder()
                    .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                    .setSampleRateHertz(16000)
                    .setLanguageCode("en-US")
                    .build()
            )
            .setAudio(
                RecognitionAudio.newBuilder()
                    .setContent(ByteString.readFrom(FileInputStream(audioFile)))
                    .build()
            )
            .build()
    )

    return response.resultsList.joinToString(" ") { it.alternativesList[0].transcript }
}

// Google Translate 翻译
fun translateText(text: String): String {
    val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=zh&dt=t&q=${text}"
    val request = Request.Builder().url(url).build()

    return OkHttpClient().newCall(request).execute().body!!.string().let { response ->
        JSONArray(response).getJSONArray(0).getJSONArray(0).getString(0)
    }
}

// 生成 SRT 字幕文件
fun generateSubtitleFile(context: Context, original: String, translated: String): File {
    val file = getAppSpecificFile(context, "subtitles.srt")
    file.writeText("1\n00:00:01,000 --> 00:00:05,000\n$original\n$translated\n")
    return file
}

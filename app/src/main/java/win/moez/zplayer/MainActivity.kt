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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.speech.v1.LongRunningRecognizeRequest
import com.google.cloud.speech.v1.RecognitionAudio
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.RecognizeRequest
import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.speech.v1.SpeechRecognitionResult
import com.google.cloud.speech.v1.SpeechSettings
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.StorageOptions
import com.google.protobuf.ByteString
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files

const val your_bucket_name = "moezplayer"

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

    Column(modifier = Modifier.fillMaxSize()) {
        Button(onClick = { pickVideoLauncher.launch("video/*") }) {
            Text("选择本地视频")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // https://mirror.aarnet.edu.au/pub/TED-talks/911Mothers_2010W-480p.mp4
        Button(onClick = { mainActivity.videoUri =
            "https://stream7.iqilu.com/10339/upload_transcode/202002/09/20200209105011F0zPoYzHry.mp4".toUri() }) {
            Text("播放在线视频")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(text = "字幕生成进度: $subtitleProgress", modifier = Modifier.padding(8.dp))

        mainActivity.videoUri?.let { uri ->
            LaunchedEffect(uri) {
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                player.play()
                extractAndProcessAudio(context, uri) { path, progress ->
                    subtitleProgress += progress
                    subtitlePath = path
                    path?.let { loadSubtitle(player, it) }
                }
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
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

@OptIn(DelicateCoroutinesApi::class)
fun extractAndProcessAudio(
    context: Context,
    videoUri: Uri,
    onProgress: (String?, String) -> Unit
) {
    val localAudioPath = getAppSpecificFile(context, "output.wav").toString()
    val credentialsStream: InputStream = context.resources.openRawResource(R.raw.a2t)
    val credentials = GoogleCredentials.fromStream(credentialsStream)

    GlobalScope.launch(Dispatchers.IO) {
        try {
            onProgress(null, "准备视频中...")
            val videoPath = getVideoFile(context, videoUri)?.toString() ?: throw IllegalArgumentException("无效的视频源")

            onProgress(null, "提取音频中...")
            val command = "-i $videoPath -y -vn -acodec pcm_s16le -ar 16000 -ac 1 $localAudioPath"
            executeFFmpegCommand(command)

            onProgress(null, "上传音频中...")
            val remoteAudioUrl = uploadAudioFile(localAudioPath, credentials)

            onProgress(null, "识别字幕中...")
            // val transcriptFile = recognizeSpeech(remoteAudioPath, credentials)
            val transcriptFile = longRunningRecognize(context, remoteAudioUrl, credentials)

            onProgress(null, "翻译字幕中...")
            val subtitlePath = translateSubtitleFile(context, transcriptFile).toString()
            onProgress(subtitlePath, "字幕已生成！")
        } catch (e: Exception) {
            Log.e("SubtitleError", "字幕处理失败", e)
            onProgress(null, "字幕生成失败:$e")
        }
    }
}

fun recognizeSpeech(audioFile: String, credentials: GoogleCredentials): File {

    // 加载服务账号密钥文件
    val settings = SpeechSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build()
    val speechClient = SpeechClient.create(settings)
    val srtFile = File(File(audioFile).parent, "subtitles.srt")
    val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(srtFile), StandardCharsets.UTF_8))

    val response = speechClient.recognize(
        RecognizeRequest.newBuilder()
            .setConfig(
                RecognitionConfig.newBuilder()
                    .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                    .setSampleRateHertz(16000)
                    .setLanguageCode("zh-CN") // 主要语言：日文
//                    .setLanguageCode("ja-JP") // 主要语言：日文
//                    .addAllAlternativeLanguageCodes(listOf("en-US", "zh-CN")) // 备用语言：英文和中文
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

fun longRunningRecognize(context: Context, url: String, credentials: GoogleCredentials): File {
    val settings = SpeechSettings.newBuilder()
        .setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build()
    val speechClient = SpeechClient.create(settings)

    val filename = url.substringAfterLast('/')
    val srtFile = getAppSpecificFile(context, "$filename.subtitles.srt")

    val audio = RecognitionAudio.newBuilder()
        .setUri(url)
        .build()

    val config = RecognitionConfig.newBuilder()
        .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
        .setSampleRateHertz(16000)
        .setLanguageCode("zh-CN") // 主要语言：日文
//                    .setLanguageCode("ja-JP") // 主要语言：日文
//                    .addAllAlternativeLanguageCodes(listOf("en-US", "zh-CN")) // 备用语言：英文和中文
        .setEnableWordTimeOffsets(true)
        .build()

    val request = LongRunningRecognizeRequest.newBuilder()
        .setConfig(config)
        .setAudio(audio)
        .build()

    val response = speechClient.longRunningRecognizeAsync(request).get()
    generateSrtFile(response.resultsList, srtFile)

    speechClient.close()
    return srtFile
}

fun generateSrtFile(results: List<SpeechRecognitionResult>, outputFile: File) {
    val srtContent = StringBuilder()
    var index = 1

    for (result in results) {
        for (alternative in result.alternativesList) {
            for (wordInfo in alternative.wordsList) {
                val startTime = wordInfo.startTime
                val endTime = wordInfo.endTime
                val transcript = wordInfo.word

                srtContent.append("$index\n")
                srtContent.append("${formatTime(startTime)} --> ${formatTime(endTime)}\n")
                srtContent.append("$transcript\n\n")
                index++
            }
        }
    }

    outputFile.writeText(srtContent.toString())
}

fun formatTime(duration: com.google.protobuf.Duration): String {
    val millis = duration.seconds * 1000 + duration.nanos / 1000000
    return "%02d:%02d:%02d,%03d".format(millis / 3600000, millis / 60000 % 60, millis / 1000 % 60, millis % 1000)
}

fun translateSubtitleFile(context: Context, srtFile: File): File {
    val translatedFile = getAppSpecificFile(context, "translated.srt")
    val translatedWriter = BufferedWriter(FileWriter(translatedFile))

    // TODO 整体翻译
    srtFile.forEachLine { line ->
        if (true || line.matches(Regex("\\d+")) || line.contains("-->")) {
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
    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(subtitlePath.toUri())
        .setMimeType("application/x-subrip")
        .build()
    player.setMediaItem(
        player.currentMediaItem!!.buildUpon().setSubtitleConfigurations(listOf(subtitleConfig)).build()
    )
    player.prepare()
}

fun copyUriToFile(context: Context, uri: Uri, outputFile: File): File? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        outputFile
    } catch (e: Exception) {
        Log.e("FFmpegKit", "Failed to copy URI", e)
        null
    }
}

fun downloadVideo(context: Context, uri: Uri, outputFile: File): File? {
    return try {
        val request = Request.Builder().url(uri.toString()).build()
        val response = OkHttpClient().newCall(request).execute()

        response.body?.byteStream()?.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        outputFile
    } catch (e: Exception) {
        Log.e("FFmpegKit", "Failed to download video", e)
        null
    }
}

fun isUrl(uri: String): Boolean {
    return try {
        URL(uri)
        true
    } catch (e: MalformedURLException) {
        false
    }
}

fun getFileName(context: Context, uri: Uri): String? {
    var fileName: String? = null
    when (uri.scheme) {
        "content" -> {
            uri.toString().split("/").lastOrNull()
        }
        "file" -> {
            fileName = File(uri.path).name
        }
        "http", "https" -> {
            try {
                val url = URL(uri.toString())
                fileName = url.path.substring(url.path.lastIndexOf('/') + 1)
            } catch (e: MalformedURLException) {
                Log.e("FFmpegKit", "Invalid URL", e)
            }
        }
    }
    return fileName
}

fun getVideoFile(context: Context, uri: Uri): File? {
    val fileName = getFileName(context, uri)?: "default_filename.mp4"
    return if (uri.toString().startsWith("content://")) {
        copyUriToFile(context, uri, getAppSpecificFile(context, fileName))
    } else if (isUrl(uri.toString())) {
        downloadVideo(context, uri, getAppSpecificFile(context, fileName))
    } else if (File(uri.toString()).exists()) {
        File(uri.toString())
    } else {
        null
    }
}

fun executeFFmpegCommand(command: String) {
    val result = runCatching {
        FFmpegKit.execute(command)
    }.getOrElse { exception ->
        throw RuntimeException("FFmpeg命令执行失败: ${exception.message}", exception)
    }

    // 检查执行结果
    if (!ReturnCode.isSuccess(result.returnCode)) {
        throw RuntimeException("FFmpeg命令执行失败，返回码: ${result.returnCode}")
    }
}

fun uploadAudioFile(filePath: String, credentials: GoogleCredentials): String {
    // 加载服务账号的JSON密钥文件
    val storage = StorageOptions.newBuilder().setCredentials(credentials).build().service
    // 获取文件路径和名称
    val file = File(filePath)
    val fileName = file.name

    // 创建BlobId和BlobInfo
    val blobId = BlobId.of(your_bucket_name, fileName)
    val blobInfo = BlobInfo.newBuilder(blobId).build()

    // 上传文件
    storage.create(blobInfo, Files.readAllBytes(file.toPath()))

    // 获取文件的URI
    val fileUri = "https://storage.googleapis.com/$your_bucket_name/$fileName"
    Log.d("Upload", "File uploaded to: $fileUri")
    return fileUri
}

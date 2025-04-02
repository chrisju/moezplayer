package win.moez.zplayer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.speech.v1.LongRunningRecognizeRequest
import com.google.cloud.speech.v1.RecognitionAudio
import com.google.cloud.speech.v1.RecognitionConfig
import com.google.cloud.speech.v1.SpeechClient
import com.google.cloud.speech.v1.SpeechContext
import com.google.cloud.speech.v1.SpeechRecognitionResult
import com.google.cloud.speech.v1.SpeechSettings
import com.google.cloud.speech.v1.WordInfo
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageOptions
import com.google.protobuf.util.Durations
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.MalformedURLException
import java.net.URL
import java.nio.ByteBuffer


const val your_bucket_name = "moezplayer"
const val split_len = 15
const val chunk_tims_ms = 1 * 60 * 1000L
const val STATIC_INDEX = 0

class MainActivity : ComponentActivity() {

    private lateinit var pickVideoLauncher: ActivityResultLauncher<String>
    internal var videoUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pickVideoLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
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

@androidx.annotation.OptIn(UnstableApi::class)
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
        Button(onClick = {
            mainActivity.videoUri =
                "https://stream7.iqilu.com/10339/upload_transcode/202002/09/20200209105011F0zPoYzHry.mp4".toUri()
        }) {
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
                    Log.d("progress", "extractAndProcessAudio:$progress")
                    subtitleProgress += progress
                    subtitlePath = path
                    path?.let { mainActivity.runOnUiThread { loadSubtitle(player, it) } }
                }
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player

                        // 设置字幕样式
                        this.subtitleView?.let {
                            val captionStyle = CaptionStyleCompat(
                                Color.WHITE,  // 字体颜色（白色）
                                Color.TRANSPARENT, // 背景颜色（透明）
                                Color.TRANSPARENT, // 窗口背景（透明）
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE, // 描边类型（黑色描边）
                                Color.BLACK, // 描边颜色
                                null // 自定义字体
                            )
                            it.setStyle(captionStyle)
                            it.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) // 调整字幕大小
                        }
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
    val audioPaths = mutableListOf<String>()
    val remoteAudioPaths = mutableListOf<String>()
    val srts = mutableListOf<File>()
    val credentialsStream: InputStream = context.resources.openRawResource(R.raw.a2t)
    val credentials = GoogleCredentials.fromStream(credentialsStream)
    var transcriptFile :File

    GlobalScope.launch(Dispatchers.IO) {
        try {
            onProgress(null, "准备视频中...")
            val videoPath = getVideoFile(context, videoUri)?.toString()
                ?: throw IllegalArgumentException("无效的视频源")

            onProgress(null, "提取音频中...")
            val command = "-i $videoPath -y -vn -acodec pcm_s16le -ar 16000 -ac 1 $localAudioPath"
            executeFFmpegCommand(command)

            if (File(localAudioPath).length() > 8 * 1024 * 1024) {
                onProgress(null, "分割音频中...")
                var list = splitWavByMilliseconds(localAudioPath, chunk_tims_ms)
                for (f in list) {
                    audioPaths.add(f)
                }
                Log.e("splitWavByMilliseconds", "分割数：${audioPaths.size}")
            } else {
                audioPaths.add(localAudioPath)
            }

            val settings = SpeechSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build()
            val speechClient = SpeechClient.create(settings)
            val static_values = mutableListOf(10)
            static_values[STATIC_INDEX] = 0
            for ((i, audioPath) in audioPaths.withIndex()) {
                onProgress(null, "上传音频中...")
                val remoteAudioPath = uploadAudioFile(audioPath, credentials)

                onProgress(null, "识别字幕中...")
                val srt = longRunningRecognize(context, remoteAudioPath, speechClient, i * chunk_tims_ms, static_values)
                srts.add(srt)

                if(srts.size == 1){
                    transcriptFile = srts[0]
                }else{
                    val filename = localAudioPath.substringAfterLast('/')
                    transcriptFile = getAppSpecificFile(context, "$filename.subtitles.srt")
                    concatenateFiles(transcriptFile, srts)
                }

                onProgress(null, "翻译字幕中...")
                val subtitlePath = translateSubtitleFile(context, transcriptFile).toString()
                onProgress(subtitlePath, "字幕已生成！")
            }
            speechClient.close()
        } catch (e: Exception) {
            Log.e("SubtitleError", "字幕处理失败", e)
            onProgress(null, "字幕生成失败:$e")
        }
    }
}

fun longRunningRecognize(
    context: Context,
    url: String,
    speechClient: SpeechClient,
    offset: Long,
    static_values: MutableList<Int>
): File {
    val filename = url.substringAfterLast('/')
    val srtFile = getAppSpecificFile(context, "$filename.subtitles.srt")

    val audio = RecognitionAudio.newBuilder()
        .setUri(url)
        .build()

    val speechContext = SpeechContext.newBuilder()
        .addAllPhrases(listOf(
            "Agile",
            "Scrum",
            "Sprint",
            "Git",
            "Docker",
            "CI/CD",
            "Unit Testing",
            "Microservices",
            "API Gateway",
            "Bug Fixing",
            "Pull Request",
            "Continuous Integration",
            "Release Notes",
            "Technical Debt"
        ))
        .build()

    val config = RecognitionConfig.newBuilder()
        .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
        .setSampleRateHertz(16000)
//        .setLanguageCode("zh-CN") // 主要语言：日文
        .setLanguageCode("ja-JP") // 主要语言：日文
        .addAllAlternativeLanguageCodes(listOf("en-US", "zh-CN")) // 备用语言：英文和中文
        .setEnableWordTimeOffsets(true)
        .setEnableAutomaticPunctuation(true)
        .setAudioChannelCount(1)
        .addSpeechContexts(speechContext)
//        .setUseEnhanced(true)
        .build()

    val request = LongRunningRecognizeRequest.newBuilder()
        .setConfig(config)
        .setAudio(audio)
        .build()

    Log.d("longRunningRecognize", "speechClient.longRunningRecognizeAsync(request).get()")
    val response = speechClient.longRunningRecognizeAsync(request).get()
    Log.d("longRunningRecognize", "speechClient.longRunningRecognizeAsync(request).get() ok")
    generateSrtFile(response.resultsList, srtFile, offset, static_values)

    return srtFile
}

fun generateSrtFile(
    results: List<SpeechRecognitionResult>,
    outputFile: File,
    offset: Long,
    static_values: MutableList<Int>
) {
    val srtContent = StringBuilder()
    var index = static_values[STATIC_INDEX] + 1

    Log.d("generateSrtFile", "sub1 1: ${results.size}")
    for (result in results) {
        Log.d("generateSrtFile", "sub1 2: ${result.alternativesList.size}")
        for (alternative in result.alternativesList) {
            Log.d("generateSrtFile", "sub1 3: ${alternative.wordsList.size}")
            val words = alternative.wordsList
            val segments = splitWordsList(words)

            for (segment in segments) {
                val startTime = Durations.add(Durations.fromMillis(offset), segment.first().startTime)
                val endTime = Durations.add(Durations.fromMillis(offset), segment.last().endTime)
                val transcript = segment.joinToString("") { if (it.word.contains("|")) it.word.split("|")[0] else it.word }

                srtContent.append("$index\n")
                srtContent.append("${formatTime(startTime)} --> ${formatTime(endTime)}\n")
                srtContent.append("$transcript\n\n")
                index++
            }
        }
    }
    static_values[STATIC_INDEX] = index - 1

    Log.d("generateSrtFile", "sub1: $srtContent")
    outputFile.writeText(srtContent.toString())
}

fun splitWordsList(words: List<WordInfo>): List<List<WordInfo>> {
    val segments = mutableListOf<List<WordInfo>>()
    var currentSegment = mutableListOf<WordInfo>()

    var len = 0
    for ((i, word) in words.withIndex()) {
        currentSegment.add(words[i])
        len += if (word.word.contains("|")) word.word.indexOf("|") else word.word.length
        if(len > split_len){
            segments.add(currentSegment.toList())
            currentSegment.clear()
            len = 0
        }
    }

    if (currentSegment.isNotEmpty()) {
        segments.add(currentSegment)
    }

    return segments
}

fun splitWordsList2(words: List<WordInfo>): List<List<WordInfo>> {
    val segments = mutableListOf<List<WordInfo>>()
    var currentSegment = mutableListOf<WordInfo>()

    var last : WordInfo? = null
    for (i in words.indices) {
        currentSegment.add(words[i])
        last?.let {
            val gap = subtractDurationsInMillis(words[i].startTime, words[i - 1].endTime)
            Log.d("words", "gap of words[${i-1}] - words[$i](${words[i].word}):$gap ms")
        }
        last = words[i]
    }

    if (currentSegment.isNotEmpty()) {
        segments.add(currentSegment)
    }

    return segments.flatMap { segment ->
        if (segment.size > 100 || segment.last().endTime.seconds - segment.first().startTime.seconds > 5) {
            splitLargeSegment(segment)
        } else {
            listOf(segment)
        }
    }
}

fun splitLargeSegment(segment: List<WordInfo>): List<List<WordInfo>> {
    val transcript = segment.joinToString("") { it.word }
    splitTextIntoSentences(transcript){
// TODO
    }
    val segments = mutableListOf<List<WordInfo>>()
    splitSegmentRecursively(segment, segments)
    return segments
}

fun splitSegmentRecursively(segment: List<WordInfo>, segments: MutableList<List<WordInfo>>) {
    if (segment.size <= 40 && (segment.last().endTime.seconds - segment.first().startTime.seconds) <= 5) {
        segments.add(segment)
    } else {
        val splitIndex = findSplitIndex(segment)
        if(splitIndex == 0){
            segments.add(segment)
        }else{
            val firstPart = segment.subList(0, splitIndex)
            val secondPart = segment.subList(splitIndex, segment.size)
            splitSegmentRecursively(firstPart, segments)
            splitSegmentRecursively(secondPart, segments)
        }
    }
}

fun findSplitIndex(segment: List<WordInfo>): Int {
    var maxGap = 0L
    var splitIndex = 0

    for (i in 1 until segment.size) {
        val gap = subtractDurationsInMillis(segment[i].startTime, segment[i - 1].endTime)
        if (gap > maxGap) {
            maxGap = gap
            splitIndex = i
        }
    }

    return splitIndex
}

fun subtractDurationsInMillis(duration1: com.google.protobuf.Duration, duration2: com.google.protobuf.Duration): Long {
    val totalNanos1 = duration1.seconds * 1_000_000_000 + duration1.nanos
    val totalNanos2 = duration2.seconds * 1_000_000_000 + duration2.nanos
    val diffNanos = totalNanos1 - totalNanos2

    return diffNanos / 1_000_000 // Convert nanoseconds to milliseconds
}

fun formatTime(duration: com.google.protobuf.Duration): String {
    val millis = duration.seconds * 1000 + duration.nanos / 1000000
    return "%02d:%02d:%02d,%03d".format(
        millis / 3600000,
        millis / 60000 % 60,
        millis / 1000 % 60,
        millis % 1000
    )
}

fun translateSubtitleFile(context: Context, srtFile: File): File {
    val translatedFile = getAppSpecificFile(context, "translated.srt")
    val translatedWriter = BufferedWriter(FileWriter(translatedFile))

    srtFile.forEachLine { line ->
//        if (line.matches(Regex("\\d+")) || line.contains("-->")) {
            translatedWriter.write(line + "\n")
//        } else {
//            translatedWriter.write(line + "\n")
//            translatedWriter.write(translateText(line) + "\n\n")
//        }
    }
    translatedWriter.close()
    return translatedFile
}

fun translateText(text: String): String {
    val url =
        "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=zh&dt=t&q=${text}"
    val request = Request.Builder().url(url).build()
    return OkHttpClient().newCall(request).execute().body!!.string().let { response ->
        JSONArray(response).getJSONArray(0).getJSONArray(0).getString(0)
    }
}

fun loadSubtitle(player: ExoPlayer, subtitlePath: String) {
    var txt = File(subtitlePath).readText()
    Log.d("loadSubtitle", "sub: $txt")

    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(subtitlePath.toUri())
        .setMimeType("application/x-subrip")
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT) // 设为默认启用
        .build()

    val currentPosition = player.currentPosition

    val playWhenReady = player.playWhenReady
    player.setMediaItem(
        player.currentMediaItem!!.buildUpon().setSubtitleConfigurations(listOf(subtitleConfig))
            .build()
    )
    player.prepare()

    player.seekTo(currentPosition)
    player.playWhenReady = playWhenReady
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
    val fileName = getFileName(context, uri) ?: "default_filename.mp4"
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

fun uploadLargeFile(storage: Storage, blobInfo: BlobInfo, filePath: String) {
    val file = File(filePath)
    val inputStream: InputStream = FileInputStream(file)
    val buffer = ByteArray(1024 * 1024) // 1 MB buffer size
    var bytesRead: Int

    val writeChannel = storage.writer(blobInfo)
    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
        writeChannel.write(ByteBuffer.wrap(buffer, 0, bytesRead))
    }
    writeChannel.close()
    inputStream.close()
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
//    storage.create(blobInfo, Files.readAllBytes(file.toPath()))
    uploadLargeFile(storage, blobInfo, filePath)

    // 获取文件的URI
    val fileUri = "gs://$your_bucket_name/$fileName"
    Log.d("Upload", "File uploaded to: $fileUri")
    return fileUri
}

private val apiKey = "YOUR_GOOGLE_CLOUD_API_KEY" // 使用你的 API 密钥
private val client = OkHttpClient()

fun splitTextIntoSentences(text: String, callback: (List<String>) -> Unit) {
    // 创建请求 JSON 数据
    val json = JSONObject()
    json.put("document", JSONObject()
        .put("type", "PLAIN_TEXT")
        .put("content", text)
    )
    json.put("encodingType", "UTF8")

    // 创建请求体
    val requestBody = RequestBody.create(
        "application/json".toMediaTypeOrNull(), json.toString()
    )

    // 创建 HTTP 请求
    val request = Request.Builder()
        .url("https://language.googleapis.com/v1/documents:analyzeSyntax?key=$apiKey")
        .post(requestBody)
        .build()

    // 发送请求
    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            e.printStackTrace()
            callback(emptyList()) // 请求失败时返回空列表
        }

        override fun onResponse(call: Call, response: Response) {
            if (response.isSuccessful) {
                // 处理成功的响应
                val responseBody = response.body?.string()
                responseBody?.let {
                    val sentences = parseSentences(it)
                    callback(sentences)
                }
            } else {
                callback(emptyList()) // 如果响应不成功，返回空列表
            }
        }
    })
}

// 解析 API 返回的句子分割数据
private fun parseSentences(response: String): List<String> {
    val sentencesList = mutableListOf<String>()
    try {
        val jsonResponse = JSONObject(response)
        val sentencesArray = jsonResponse.getJSONArray("sentences")

        // 遍历每个句子并将其添加到列表
        for (i in 0 until sentencesArray.length()) {
            val sentence = sentencesArray.getJSONObject(i)
            val sentenceText = sentence.getJSONObject("text").getString("content")
            sentencesList.add(sentenceText)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return sentencesList
}

fun splitWavByMilliseconds(inputPath: String, segmentDurationMs: Long): List<String> {
    val inputFile = File(inputPath)
    if (!inputFile.exists()) throw FileNotFoundException("文件不存在: $inputPath")

    // 读取 WAV 头部
    val inputStream = FileInputStream(inputFile)
    val header = ByteArray(44)
    inputStream.read(header)

    // 解析 WAV 头部信息
    val sampleRate = ((header[27].toInt() and 0xFF) shl 24) or
            ((header[26].toInt() and 0xFF) shl 16) or
            ((header[25].toInt() and 0xFF) shl 8) or
            (header[24].toInt() and 0xFF)
    val channels = header[22].toInt()
    val bitsPerSample = header[34].toInt()
    val byteRate = sampleRate * channels * (bitsPerSample / 8)  // 每秒的字节数

    println("采样率: $sampleRate Hz, 通道数: $channels, 位深: $bitsPerSample bit, 每秒字节数: $byteRate")

    // 计算每个段的字节大小
    val chunkSize = ((segmentDurationMs * byteRate) / 1000).toInt()
    val buffer = ByteArray(4096)

    val outputFiles = mutableListOf<String>()
    var chunkIndex = 1
    var startByte = 44  // 数据起始位置
    val totalSize = inputFile.length().toInt()

    while (startByte < totalSize) {
        val outputFile = File("${inputPath}${chunkIndex}.wav")
        FileOutputStream(outputFile).use { outputStream ->
            outputStream.write(header) // 写入 WAV 头部

            // 读取数据
            inputStream.channel.position(startByte.toLong())
            var bytesRemaining = chunkSize
            var bytesRead: Int
            while (bytesRemaining > 0) {
                val readSize = if (bytesRemaining < buffer.size) bytesRemaining else buffer.size
                bytesRead = inputStream.read(buffer, 0, readSize)
                if (bytesRead == -1) break
                outputStream.write(buffer, 0, bytesRead)
                bytesRemaining -= bytesRead
            }

            // 计算实际数据大小
            val actualDataSize = chunkSize - bytesRemaining
            if (actualDataSize <= 0) return@splitWavByMilliseconds emptyList()

            // 更新 WAV 头部
            updateWavHeader(outputFile, actualDataSize)

            // 保存路径
            outputFiles.add(outputFile.absolutePath)
        }

        println("生成文件: ${outputFile.absolutePath}")
        chunkIndex++
        startByte += chunkSize
    }

    inputStream.close()
    return outputFiles
}

/**
 * 更新 WAV 头部信息（修正文件大小）
 */
fun updateWavHeader(file: File, dataSize: Int) {
    RandomAccessFile(file, "rw").use { raf ->
        val riffSize = dataSize + 36
        val riffSizeBytes = ByteArray(4)
        val dataSizeBytes = ByteArray(4)

        for (i in 0..3) {
            riffSizeBytes[i] = ((riffSize shr (i * 8)) and 0xFF).toByte()
            dataSizeBytes[i] = ((dataSize shr (i * 8)) and 0xFF).toByte()
        }

        raf.seek(4)
        raf.write(riffSizeBytes) // 更新 RIFF 块大小
        raf.seek(40)
        raf.write(dataSizeBytes) // 更新数据块大小
    }
}

fun concatenateFiles(outputFile: File, inputFiles: List<File>) {
    FileOutputStream(outputFile).use { outputStream ->
        inputFiles.forEach { inputFile ->
            inputFile.inputStream().use { inputStream ->
                copyStream(inputStream, outputStream)
            }
        }
    }
}

fun copyStream(inputStream: InputStream, outputStream: FileOutputStream) {
    val buffer = ByteArray(1024)
    var bytesRead: Int
    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
        outputStream.write(buffer, 0, bytesRead)
    }
}

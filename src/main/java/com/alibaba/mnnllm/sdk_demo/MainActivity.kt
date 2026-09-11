package com.alibaba.mnnllm.sdk_demo

import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.mnnllm.ondeviceai.OnDeviceAiEngine
import java.io.File
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var engine: OnDeviceAiEngine
    private lateinit var responseText: TextView
    private lateinit var promptInput: EditText
    private lateinit var modelPathInput: EditText
    private lateinit var systemPromptInput: EditText
    private lateinit var tempInput: EditText
    private lateinit var noThinkCheckBox: CheckBox
    private lateinit var debugModeCheckBox: CheckBox
    private lateinit var promptCacheCheckBox: CheckBox
    private lateinit var streamCheckBox: CheckBox
    private lateinit var rbGpu: RadioButton
    private lateinit var perfMetricsText: TextView
    private lateinit var imageStatusText: TextView
    private lateinit var sendButton: Button
    private lateinit var initButton: Button
    private val selectedImages = mutableListOf<OnDeviceAiEngine.UploadedImage>()
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importSelectedImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        engine = OnDeviceAiEngine()
        responseText = findViewById(R.id.responseText)
        perfMetricsText = findViewById(R.id.perfMetricsText)
        imageStatusText = findViewById(R.id.imageStatusText)
        promptInput = findViewById(R.id.promptInput)
        modelPathInput = findViewById(R.id.modelPathInput)
        systemPromptInput = findViewById(R.id.systemPromptInput)
        tempInput = findViewById(R.id.tempInput)
        noThinkCheckBox = findViewById(R.id.noThinkCheckBox)
        debugModeCheckBox = findViewById(R.id.debugModeCheckBox)
        promptCacheCheckBox = findViewById(R.id.promptCacheCheckBox)
        streamCheckBox = findViewById(R.id.streamCheckBox)
        rbGpu = findViewById(R.id.rbGpu)
        sendButton = findViewById(R.id.sendButton)
        initButton = findViewById(R.id.initButton)

        systemPromptInput.setLines(1)

        systemPromptInput.setText(
            "English request classifier.\n" +
                    "Unsafe/illegal/harmful -> REJECT \n" +
                    "Supported vehicle control -> exact command \n" +
                    "Otherwise -> UNKNOWN \n" +
                    "Priority: REJECT > vehicle command > UNKNOWN.\n" +
                    "Output only the result."
        )

        promptInput.setText("open the window to 40%")

        debugModeCheckBox.setOnCheckedChangeListener { _, isChecked ->
            engine.setDebugMode(isChecked)
            Toast.makeText(this, "调试日志已${if (isChecked) "开启" else "关闭"}", Toast.LENGTH_SHORT).show()
        }

        noThinkCheckBox.setOnCheckedChangeListener { _, isChecked ->
            engine.setThinking(isChecked)
            Toast.makeText(this, "已${if (isChecked) "启用" else "禁用"}Thinking引导", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.clearHistoryButton).setOnClickListener {
            engine.reset()
            responseText.text = "对话历史已清空"
            Toast.makeText(this, "历史记录已清空", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.initButton).setOnClickListener {
            val path = modelPathInput.text.toString()
            val systemPrompt = systemPromptInput.text.toString()
            val backend = if (rbGpu.isChecked) "opencl" else "cpu"
            val temperature = tempInput.text.toString().toFloatOrNull() ?: 0.6f
            
            thread {
                val config = OnDeviceAiEngine.PerformanceConfig(
                    backendType = backend,
                    threadNum = 4,
                    precision = "low",
                    promptCache = promptCacheCheckBox.isChecked,
                    reuseKv = true,
                    maxHistory = 1,
                    temperature = temperature,
                    topP = 0.1f,
                    topK = 1,
                    maxNewTokens = 64,
                    samplerType = "greedy"
                )
                val success = engine.init(path, config, systemPrompt, isThinking = false)
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "模型初始化成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "模型初始化失败，请检查路径", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        findViewById<Button>(R.id.uploadImageButton).setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }

        findViewById<Button>(R.id.clearImageButton).setOnClickListener {
            selectedImages.clear()
            imageStatusText.text = "未选择图片"
            Toast.makeText(this, "已清除图片", Toast.LENGTH_SHORT).show()
        }

        sendButton.setOnClickListener {
            val prompt = promptInput.text.toString().trim()
            if (prompt.isEmpty()) return@setOnClickListener

            val isStreaming = streamCheckBox.isChecked
            val requestImages = selectedImages.toList()
            responseText.text = if (isStreaming) "正在流式思考..." else "正在同步生成 (请稍候)..."
            
            initButton.isEnabled = false

            thread {
                if (isStreaming) {
                    val rawResponse = StringBuilder()
                    var sawEnd = false
                    engine.chat(prompt, requestImages, object : OnDeviceAiEngine.ChatCallback {
                        override fun onToken(token: String, isEnd: Boolean): Boolean {
                            if (isEnd) {
                                sawEnd = true
                            }
                            runOnUiThread {
                                if (!isEnd) {
                                    rawResponse.append(token)
                                    if (responseText.text.startsWith("正在流式")) {
                                        responseText.text = "正在生成..."
                                    } else {
                                        responseText.text = "正在生成..."
                                    }
                                } else {
                                    responseText.text = normalizeCommandResponse(rawResponse.toString())
                                    responseText.append("\n\n[回答结束]")
                                    initButton.isEnabled = true
                                }
                            }
                            return true
                        }

                        override fun onPerformanceUpdate(prefillTimeMs: Long, decodeTimeMs: Long, tokenCount: Int) {
                            runOnUiThread {
                                val tps = if (decodeTimeMs > 0) (tokenCount * 1000.0 / decodeTimeMs) else 0.0
                                perfMetricsText.text = "性能指标: Prefill: ${prefillTimeMs}ms | Decode: ${decodeTimeMs}ms | Tokens: $tokenCount | Speed: %.2f t/s".format(tps)
                            }
                        }
                    })
                    if (!sawEnd) {
                        runOnUiThread {
                            responseText.text = normalizeCommandResponse(rawResponse.toString())
                            responseText.append("\n\n[回答结束]")
                            initButton.isEnabled = true
                        }
                    }
                } else {
                    val fullResponse = engine.chatSync(prompt, requestImages)
                    runOnUiThread {
                        responseText.text = normalizeCommandResponse(fullResponse)
                        responseText.append("\n\n[同步回答结束]")
                        initButton.isEnabled = true
                    }
                }
            }
        }

        findViewById<Button>(R.id.stopButton).setOnClickListener {
            engine.stopChat()
            Toast.makeText(this, "正在中止生成...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importSelectedImage(uri: Uri) {
        try {
            val uploadedImage = engine.uploadImage(this, uri)
            selectedImages.clear()
            selectedImages.add(uploadedImage)
            imageStatusText.text = File(uploadedImage.path).name
            Toast.makeText(this, "图片已导入", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            imageStatusText.text = "图片导入失败"
            Toast.makeText(this, "图片导入失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.release()
    }

    private fun normalizeCommandResponse(raw: String?): String {
        val cleaned = raw.orEmpty()
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .replace("标准指令：", "")
            .replace("标准指令:", "")
            .replace("输出：", "")
            .replace("输出:", "")
            .trim()

        val candidate = cleaned
            .lineSequence()
            .map { it.trim().trim('"', '\'', '“', '”') }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
            .trimEnd('。', '.', '；', ';')

        val invalidHints = listOf("我需要", "首先", "好的", "用户输入", "分析", "解释", "步骤", "任务")
        if (candidate.isBlank() || invalidHints.any { candidate.contains(it) }) {
            return "无法识别指令"
        }
        return candidate
    }

}

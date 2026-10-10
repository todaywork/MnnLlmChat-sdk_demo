package com.alibaba.mnnllm.sdk_demo

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.alibaba.mnnllm.ondeviceai.OnDeviceAiEngine
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class BatchActivity : AppCompatActivity() {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var running = false
    @Volatile private var engine: OnDeviceAiEngine? = null
    private lateinit var status: TextView
    private lateinit var manual: EditText
    private lateinit var source: RadioGroup
    private lateinit var cache: CheckBox
    private lateinit var start: Button
    private lateinit var pick: Button
    private lateinit var save: Button
    private var selected: File? = null
    private var output: File? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !running) {
            try {
                val file = File(cacheDir, "batch-input.xlsx")
                contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                selected = file
                source.check(2)
                status.text = "Excel 已导入（第一行表头，A 列语料，B 列预期结果）"
            } catch (e: Exception) { status.text = "导入失败：${e.message}" }
        }
    }
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) try {
            output?.inputStream()?.use { input -> contentResolver.openOutputStream(uri)!!.use { input.copyTo(it) } }
            status.append("\n结果已另存")
        } catch (e: Exception) { status.append("\n导出失败：${e.message}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "自动化批跑"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        setContentView(ScrollView(this).apply { addView(root) })
        fun label(text: String) = root.addView(TextView(this).apply { this.text = text; textSize = 16f })
        label("逐条推理，完成后导出 Excel")
        label("使用主页面的模型、System Prompt、后端和温度。可在下方切换缓存。")
        source = RadioGroup(this).apply {
            addView(RadioButton(this@BatchActivity).apply { id = 1; text = "手动输入：每行语料，可用 Tab 分隔预期结果" })
            addView(RadioButton(this@BatchActivity).apply { id = 2; text = "导入的 Excel / ADB 推送文件" })
            check(1)
        }
        root.addView(source)
        manual = EditText(this).apply {
            minLines = 4; textSize = 14f; gravity = android.view.Gravity.TOP
            setText("open the window\nturn up volume\nset volume to 40%")
        }
        root.addView(manual)
        pick = Button(this).apply { text = "导入 Excel"; setOnClickListener { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) } }
        root.addView(pick)
        cache = CheckBox(this).apply { text = "复用 Prompt Cache（关闭可作全量预填对照）"; isChecked = intent.getBooleanExtra("prompt_cache", true) }
        root.addView(cache)
        start = Button(this).apply { text = "开始批跑"; setOnClickListener { startBatch() } }
        root.addView(start)
        root.addView(Button(this).apply { text = "停止批跑并保留结果"; setOnClickListener { cancelled.set(true); engine?.stopChat() } })
        save = Button(this).apply { text = "另存结果 Excel"; isEnabled = false; setOnClickListener { exporter.launch(output!!.name) } }
        root.addView(save)
        status = TextView(this).apply { text = "等待批跑"; setTextIsSelectable(true) }
        root.addView(status)
        intent.getStringExtra("batch_input")?.let { selected = File(it); source.check(2); status.text = "输入文件：$it" }
        if (intent.getBooleanExtra("batch_auto_start", false)) start.post { startBatch() }
    }

    private fun event(runId: String, type: String, fields: String) {
        Log.i("SdkBatch", "BATCH_AUTOMATION EVENT=$type run=$runId $fields")
    }

    private fun startBatch() {
        if (running) return
        val runId = intent.getStringExtra("batch_run_id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) } ?: UUID.randomUUID().toString()
        intent.removeExtra("batch_run_id")
        output = null
        val fromFile = source.checkedRadioButtonId == 2
        val inputFile = selected
        val text = manual.text.toString()
        val useCache = cache.isChecked
        running = true; cancelled.set(false)
        start.isEnabled = false; pick.isEnabled = false; save.isEnabled = false; cache.isEnabled = false; manual.isEnabled = false
        for (i in 0 until source.childCount) source.getChildAt(i).isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status.text = "正在读取语料、初始化模型…"
        thread(name = "sdk-batch") {
            val sdk = OnDeviceAiEngine()
            engine = sdk
            val results = mutableListOf<BatchResult>()
            val resultFile = File(filesDir, "batch_results/batch_$runId.xlsx")
            try {
                val inputs = if (fromFile) {
                    requireNotNull(inputFile) { "请先导入 Excel" }
                    BatchXlsxReader.readInputs(inputFile)
                } else text.lineSequence().filter { it.isNotBlank() }.map {
                    val columns = it.split('\t', limit = 2)
                    BatchInput(columns[0].trim(), columns.getOrElse(1) { "" }.trim())
                }.filter { it.prompt.isNotEmpty() }.toList()
                require(inputs.isNotEmpty()) { "没有有效语料" }
                event(runId, "STARTED", "total=${inputs.size} cache=$useCache")
                val config = OnDeviceAiEngine.PerformanceConfig(
                    backendType = intent.getStringExtra("backend") ?: "cpu", promptCache = useCache,
                    reuseKv = true, maxHistory = 1, temperature = intent.getFloatExtra("temperature", 0f),
                    topP = 0.1f, topK = 1, maxNewTokens = 64, samplerType = "greedy")
                check(sdk.init(intent.getStringExtra("model_path") ?: "/data/local/tmp/qwen3-car-mnn-int4",
                    config, intent.getStringExtra("system_prompt") ?: DEFAULT_SYSTEM, false)) { "模型初始化失败" }
                for ((i, input) in inputs.withIndex()) {
                    if (cancelled.get()) break
                    val raw = StringBuilder()
                    val metrics = RunMetrics()
                    val result = try {
                        sdk.chat(input.prompt, object : OnDeviceAiEngine.ChatCallback {
                            override fun onToken(token: String, isEnd: Boolean): Boolean {
                                if (!isEnd) raw.append(token)
                                return !cancelled.get()
                            }
                            override fun onPerformanceUpdate(prefillTimeMs: Long, decodeTimeMs: Long, tokenCount: Int) {
                                metrics.prefillSeconds = prefillTimeMs / 1000.0
                                metrics.decodeSeconds = decodeTimeMs / 1000.0
                                metrics.generatedTokens = tokenCount.toLong()
                                metrics.generatedTokensPerSecond = if (decodeTimeMs > 0) tokenCount * 1000.0 / decodeTimeMs else 0.0
                            }
                        })
                        check(!cancelled.get()) { "用户停止；部分输出：$raw" }
                        check(raw.isNotBlank()) { "模型返回空响应" }
                        BatchResult.success(i + 1, input.prompt, input.expected,
                            "prompt_cache=$useCache; SDK=${sdk.version}; prompt token count unavailable", raw.toString().trim(), metrics)
                    } catch (e: Exception) {
                        sdk.reset()
                        BatchResult.failure(i + 1, input.prompt, input.expected, "prompt_cache=$useCache", e)
                    }
                    results.add(result)
                    // Checkpoint each item so stopping or process death preserves completed rows.
                    val temporary = File(resultFile.parentFile, resultFile.name + ".tmp")
                    BatchXlsxWriter.write(temporary, results)
                    check(temporary.renameTo(resultFile)) { "无法保存批跑结果" }
                    runOnUiThread { status.text = "进度 ${i + 1}/${inputs.size}\n${input.prompt}\n${result.output}\n${result.compareResult}\n${result.error.orEmpty()}" }
                    event(runId, "PROGRESS", "processed=${results.size} total=${inputs.size}")
                }
                val failed = results.count { it.error != null }
                val mismatch = results.count { it.compareResult == "不一致" }
                val state = when { cancelled.get() -> "stopped"; failed > 0 -> "partial_failure"; results.isEmpty() -> "no_results"; else -> "success" }
                output = resultFile.takeIf { it.exists() }
                event(runId, "COMPLETED", "status=$state processed=${results.size} failed=$failed mismatched=$mismatch total=${inputs.size} output=${output?.absolutePath ?: "none"}")
                runOnUiThread { status.text = "批跑结束：$state\n已处理 ${results.size}/${inputs.size}，失败 $failed，不一致 $mismatch\n${output?.absolutePath.orEmpty()}" }
            } catch (e: Exception) {
                output = resultFile.takeIf { it.exists() }
                event(runId, "FAILED", "error=${e.javaClass.simpleName}")
                Log.e("SdkBatch", "Batch failed", e)
                runOnUiThread { status.text = "批跑失败：${e.message}\n已保存 ${results.size} 条\n${output?.absolutePath.orEmpty()}" }
            } finally {
                sdk.release(); engine = null; running = false
                runOnUiThread {
                    start.isEnabled = true; pick.isEnabled = true; cache.isEnabled = true; manual.isEnabled = true
                    save.isEnabled = output != null
                    for (i in 0 until source.childCount) source.getChildAt(i).isEnabled = true
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    override fun onDestroy() {
        cancelled.set(true); engine?.stopChat()
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_SYSTEM = "English request classifier.\nUnsafe/illegal/harmful -> REJECT \nSupported vehicle control -> exact command \nOtherwise -> UNKNOWN \nPriority: REJECT > vehicle command > UNKNOWN.\nOutput only the result."
    }
}

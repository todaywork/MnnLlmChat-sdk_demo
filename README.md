# OnDeviceAi SDK Demo（v1.0.17）

`sdk_demo` 用于演示和验证 `on_device_ai` AAR 的模型初始化、文本生成、流式回调、性能指标和图片输入能力。

新增 **自动化批跑**：主界面点击“自动化批跑”，支持手动多行语料、Excel 导入、预期结果比对、缓存开关对照、停止保留结果和 Excel 导出。电脑可用脚本自动推送、启动并拉取结果，详见 [批跑使用说明](scripts/README.md)。

## 版本迭代

- **v1.0.17**：修正 SDK System Prompt 预填充为 `reset_keep_system()`；禁用 `src/main/jniLibs` 的旧 MNN 库覆盖，使用 CMake 导入的修复版运行库。真机缓存开关对照、重复请求及重置后请求共 12 次推理通过。详见 [本次分析与构建记录](pc_batch_test/ANALYSIS_20260911.md)。

- **v1.0.16**：修复引擎 prompt_cache 复用路径两个导致堆损坏/输出截断的问题。① 空 delta：重发与 KV 完全一致的 prompt（如上一轮 0 token 被 stop/超时中止后重发同一句、keep_history=false 重复发送）时，最长公共前缀匹配使 delta 为空，0 长度 forward 越界写堆内存，之后 `Executor::gc` → `SingleBufferWithAllocator::release` → `free` 检测到 chunk tag 损坏而 abort；现回退为全量重 prefill。② 记账错位：`history_tokens` 比有效 KV（`all_seq_len`）长时（eraseHistory 部分回退 FIXME、forward 异常返回等路径），append delta 后文本记账与 KV 物理位置错位，`updateCachedPromptText` 按错误偏移解码响应，缓存文本污染导致后续轮次输出 tag 被截断/错乱；现钳制复用长度的同时把 `history_tokens` 截断对齐。
- **v1.0.12**：修复开启 Prompt Cache 后推理输出 UNKNOWN 的问题。System Prompt 预填改用引擎 `reset_keep_system()`（不再把 generation header 写入 KV）；引擎 prompt_cache 的 token 边界改用与 `history_tokens` 最长公共前缀匹配，修复 mtok tokenizer 下 token_split 漂移导致用户指令中间段未 prefill 的问题。实机回归：`open the window`→`ON device carWindow`、`turn up volume`→`TURN_UP_VOLUME`、`set volume to 40%`→`SET_VOLUME number 0.4`，与关缓存基准一致。
- **v1.0.11**：升级 MNN 工具链及 3.6.1 Runtime，解决新模型导出后的加载兼容问题，并支持新版结构化 `tie_embeddings` 模型。
- **v1.0.10**：修复 AI 推理过程中可能发生的 crash 问题。
- **v1.0.9**：增加多模态图片输入及会话稳定性修复。

## 1. 当前依赖

运行库随 SDK 工程保存于 `on_device_ai/src/main/jniLibs/arm64-v8a/libMNN.so`。
CMake 直接从该路径链接，AGP 将同一份库打入 AAR，不再从外部 `build_64/lib` 读取。
Gradle 保留 `sourceSets.main.jniLibs.srcDirs = []`，只关闭重复的目录扫描；该文件仍通过 CMake 的 imported target 正常打包。
JNI 编译所需的 MNN 头文件仍通过 `MNN_ROOT` 指向匹配版本的源码，本次未将头文件复制到工程。

Demo 直接引用本项目最新构建的 AAR：

```groovy
implementation files("../on_device_ai/build/outputs/aar/on_device_ai-1.0.17-release.aar")
```

如需交付独立 Demo 工程，可将 AAR 复制到 `sdk_demo/libs` 后改为：

```groovy
implementation files("libs/on_device_ai-1.0.17-release.aar")
```

## 2. 当前默认配置

默认模型路径：

```text
/data/local/tmp/qwen3-car-mnn-int4
```

默认 System Prompt：

```text
You are a multilingual vehicle control assistant. Extract intent and slots from user commands in any language. Always respond with English JSON only.
```

默认推理参数：

| 参数 | 值 |
|---|---:|
| Backend | `cpu` |
| Thread | 4 |
| Precision | `low` |
| Prompt Cache | 开启 |
| Reuse KV | 开启 |
| Max History | 1 |
| Temperature | `0.0` |
| Top P | `0.1` |
| Top K | 1 |
| Max New Tokens | 64 |
| Sampler | `greedy` |
| Thinking | 关闭 |

界面仍允许修改模型路径、System Prompt、温度、CPU/GPU 后端、Prompt Cache 和 Thinking 开关。

## 3. 准备模型

确认设备已连接：

```powershell
adb devices -l
```

确认模型文件：

```powershell
adb shell ls -lh /data/local/tmp/qwen3-car-mnn-int4
```

目录至少应包含：

```text
config.json
llm_config.json
llm.mnn
llm.mnn.weight
tokenizer.mtok
```

该模型使用内嵌 embedding，不需要 `embeddings_bf16.bin`。

## 4. 编译并安装

先构建 AAR：

```powershell
$env:MNN_ROOT = "E:\LLMProject\MNN"
.\gradlew.bat :on_device_ai:assembleRelease --rerun-tasks
```

再构建 Demo：

```powershell
.\gradlew.bat :sdk_demo:assembleDebug
```

安装：

```powershell
adb install -r sdk_demo\build\outputs\apk\debug\sdk_demo-debug.apk
```

APK 输出：

```text
sdk_demo/build/outputs/apk/debug/sdk_demo-debug.apk
```

## 5. 验证初始化

点击“初始化”后，成功日志应包含：

```text
Load() finished. Result: 1
```

不应再出现：

```text
Can't open file: .../embeddings_bf16.bin
Failed to open embedding file!
```

过滤日志：

```powershell
adb logcat | Select-String "MNN_PRINT|MNN_DEBUG|MNNJNI|embeddings_bf16"
```

## 6. 功能说明

- **初始化**：释放旧 Session，按界面配置加载模型并预填充 System Prompt。
- **发送**：支持流式和同步生成。
- **停**：请求中止当前生成。
- **重置**：清空当前会话历史和 KV 状态。
- **选图**：通过系统文件选择器导入图片至 SDK cache。
- **清图**：清除当前 Demo 的图片选择状态。
- **性能指标**：显示 Prefill、Decode、Token 数和 Decode 速度。

## 7. 已验证环境

- 设备：arm64 Android 设备
- MNN Runtime：3.6.1
- SDK AAR：1.0.11
- 模型：`/data/local/tmp/qwen3-car-mnn-int4`
- 结果：模型初始化成功，System Prompt Prefill 成功，文本推理成功

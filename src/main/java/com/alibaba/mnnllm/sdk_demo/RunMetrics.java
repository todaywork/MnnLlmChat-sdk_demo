package com.alibaba.mnnllm.sdk_demo;

final class RunMetrics {
    double prefillSeconds;
    double decodeSeconds;
    double promptTokensPerSecond;
    double generatedTokensPerSecond;
    // The SDK callback does not expose prompt token counts.
    long promptTokens = -1;
    long generatedTokens;
}

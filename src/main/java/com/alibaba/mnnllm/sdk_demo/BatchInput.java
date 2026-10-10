package com.alibaba.mnnllm.sdk_demo;

final class BatchInput {
    final String prompt;
    final String expected;
    BatchInput(String prompt, String expected) {
        this.prompt = prompt;
        this.expected = expected;
    }
}

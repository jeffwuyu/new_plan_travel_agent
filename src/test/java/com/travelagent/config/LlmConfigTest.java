package com.travelagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LlmConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LlmConfig.class)
            .withPropertyValues(
                    "dashscope.api-key=your_dashscope_api_key_here",
                    "dashscope.llm.model=qwen-plus",
                    "dashscope.llm.temperature=0.7",
                    "dashscope.timeout=60000"
            );

    @Test
    @DisplayName("LlmConfig creates ChatModel from dashscope properties")
    void llmConfigCreatesChatModelFromDashscopeProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ChatModel.class);
            assertThat(context).hasBean("dashscopeChatModel");
        });
    }
}

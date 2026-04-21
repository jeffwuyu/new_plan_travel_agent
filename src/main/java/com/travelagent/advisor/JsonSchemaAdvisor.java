package com.travelagent.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Forces the model to answer in a JSON shape agreed by the caller.
 */
@Component
public class JsonSchemaAdvisor implements BaseAdvisor {

    public static final String NAME = "jsonSchema";

    private static final int ORDER = 300;

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        String addition = buildSchemaInstructions(request.context());
        if (addition.isBlank()) {
            return request;
        }
        return request.mutate()
                .prompt(appendSystemText(request.prompt(), addition))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        return response;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @SuppressWarnings("unchecked")
    String buildSchemaInstructions(Map<String, Object> context) {
        Object rawSchema = context.get(AdvisorContextKeys.RESPONSE_SCHEMA);
        if (!(rawSchema instanceof Map<?, ?> schema)) {
            return "";
        }

        List<String> required = List.of();
        Object requiredValue = schema.get("required");
        if (requiredValue instanceof List<?> list) {
            required = list.stream().map(Object::toString).collect(Collectors.toList());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【输出格式强制要求】\n");
        sb.append("1. 你的回复必须以 '{' 开头，以 '}' 结尾，中间是合法 JSON 对象。\n");
        sb.append("2. 不得使用 Markdown 代码块（禁止 ``` 或 ```json）。\n");
        sb.append("3. JSON 前后不得有任何解释性文字、前言或后记。\n");
        sb.append("4. 所有中文内容字段的值使用中文输出。\n");
        Object type = schema.get("type");
        if (type != null) {
            sb.append("5. 顶层 JSON 类型：").append(type).append("。\n");
        }
        if (!required.isEmpty()) {
            sb.append("6. 必填字段（缺少则响应无效）：")
              .append(String.join(", ", required)).append("。\n");
        }
        sb.append("\n示例合法响应：{\"attractionName\": \"兵马俑\", \"reason\": \"秦文化代表，适合历史爱好者\"}");
        return sb.toString();
    }

    private Prompt appendSystemText(Prompt prompt, String addition) {
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        int systemIndex = findSystemMessageIndex(messages);
        if (systemIndex >= 0) {
            SystemMessage existing = (SystemMessage) messages.get(systemIndex);
            messages.set(systemIndex, new SystemMessage(mergeText(existing.getText(), addition)));
        } else {
            messages.add(0, new SystemMessage(addition));
        }
        return new Prompt(messages, prompt.getOptions());
    }

    private int findSystemMessageIndex(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) {
                return i;
            }
        }
        return -1;
    }

    private String mergeText(String existing, String addition) {
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        return existing + "\n\n" + addition;
    }
}

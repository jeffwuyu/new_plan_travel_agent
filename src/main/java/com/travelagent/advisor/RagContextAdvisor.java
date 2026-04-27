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
 * Injects retrieved travel-guide snippets into the system prompt.
 */
@Component
public class RagContextAdvisor implements BaseAdvisor {

    public static final String NAME = "ragContext";

    private static final int ORDER = 200;

    /**
     * 处理before。
     * @param request 请求参数
     * @param advisorChain a dv is or Ch ai n 参数
     * @return 返回处理结果。
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        String addition = buildRagInstructions(request.context());
        if (addition.isBlank()) {
            return request;
        }
        return request.mutate()
                .prompt(appendSystemText(request.prompt(), addition))
                .build();
    }

    /**
     * 处理after。
     * @param response 响应参数
     * @param advisorChain a dv is or Ch ai n 参数
     * @return 返回处理结果。
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        return response;
    }

    /**
     * 获取name。
     * @return 返回处理结果。
     */
    @Override
    public String getName() {
        return NAME;
    }

    /**
     * 获取order。
     * @return 返回处理结果。
     */
    @Override
    public int getOrder() {
        return ORDER;
    }

    @SuppressWarnings("unchecked")
    String buildRagInstructions(Map<String, Object> context) {
        Object rawChunks = context.get(AdvisorContextKeys.RAG_CHUNKS);
        if (!(rawChunks instanceof List<?> list)) {
            return "";
        }
        List<String> chunks = list.stream()
                .map(Object::toString)
                .map(String::trim)
                .filter(text -> !text.isBlank())
                .collect(Collectors.toList());
        if (chunks.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Reference information from travel guides:\n");
        for (String chunk : chunks) {
            sb.append("- ").append(chunk).append("\n");
        }
        sb.append("Use the reference information when relevant, but keep the answer grounded in the requested JSON output.");
        return sb.toString();
    }

    /**
     * 处理appendSystemText。
     * @param prompt p ro mp t 参数
     * @param addition a dd it io n 参数
     * @return 返回处理结果。
     */
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

    /**
     * 查找systemmessageindex。
     * @param messages m es sa ge s 参数
     * @return 返回处理结果。
     */
    private int findSystemMessageIndex(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 合并text。
     * @param existing e xi st in g 参数
     * @param addition a dd it io n 参数
     * @return 返回处理结果。
     */
    private String mergeText(String existing, String addition) {
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        return existing + "\n\n" + addition;
    }
}

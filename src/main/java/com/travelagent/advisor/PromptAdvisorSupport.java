package com.travelagent.advisor;

import com.travelagent.agent.prompt.PromptAssembly;
import com.travelagent.agent.prompt.PromptSectionType;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

final class PromptAdvisorSupport {

    private PromptAdvisorSupport() {
    }

    static Prompt appendSection(Prompt prompt, PromptSectionType type, String addition) {
        if (addition == null || addition.isBlank()) {
            return prompt;
        }
        List<Message> messages = new ArrayList<>(prompt.getInstructions());
        int systemIndex = findSystemMessageIndex(messages);
        String renderedAddition = PromptAssembly.create()
                .add(type, addition)
                .render();
        if (systemIndex >= 0) {
            SystemMessage existing = (SystemMessage) messages.get(systemIndex);
            messages.set(systemIndex, new SystemMessage(mergeText(existing.getText(), renderedAddition)));
        } else {
            messages.add(0, new SystemMessage(renderedAddition));
        }
        return new Prompt(messages, prompt.getOptions());
    }

    private static int findSystemMessageIndex(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage) {
                return i;
            }
        }
        return -1;
    }

    private static String mergeText(String existing, String addition) {
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        return existing + "\n\n" + addition;
    }
}

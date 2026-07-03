package com.youdub.replica.service.adapter.translate;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public abstract class AbstractTranslator implements Translator {

    protected record Utterance(String text, long startTime, long endTime, String speaker) {
    }

    /**
     * 合并从句片段：如果某句以逗号结尾而下一句以小写开头，
     * 说明 ASR 将一句话断成了两段，需要合并后再翻译，避免 LLM 脑补。
     */
    protected List<Utterance> mergeFragments(List<Utterance> items) {
        if (items.isEmpty()) return items;
        List<Utterance> result = new ArrayList<>();
        int i = 0;
        while (i < items.size()) {
            StringBuilder text = new StringBuilder(items.get(i).text);
            long startTime = items.get(i).startTime;
            long endTime = items.get(i).endTime;
            String speaker = items.get(i).speaker;
            int j = i;
            while (j < items.size() - 1
                    && items.get(j).text.endsWith(",")
                    && !items.get(j + 1).text.isEmpty()
                    && Character.isLowerCase(items.get(j + 1).text.charAt(0))) {
                j++;
                text.append(" ").append(items.get(j).text);
                endTime = items.get(j).endTime;
            }
            result.add(new Utterance(text.toString(), startTime, endTime, speaker));
            i = j + 1;
        }
        if (result.size() != items.size()) {
            log.info("合并了 {} 个从句片段（原始 {} 句 → 合并后 {} 句）",
                    items.size() - result.size(), items.size(), result.size());
        }
        return result;
    }
}

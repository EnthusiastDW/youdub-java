package com.youdub.replica.service.adapter.asr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youdub.replica.config.AppProperties;
import com.youdub.replica.model.entity.Task;
import com.youdub.replica.service.SettingsService;
import com.youdub.replica.util.Command;
import com.youdub.replica.util.CommandRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import static com.youdub.replica.service.adapter.AdapterConstants.WHISPER_CPP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * whisper.cpp 语音识别适配器。
 * 通过 whisper-cli 子进程进行本地语音识别。
 */
@Slf4j
@Component(WHISPER_CPP)
@RequiredArgsConstructor
public class WhisperCppRecognizer implements SpeechRecognizer {

    private static final long TIMEOUT_MS = 600_000L;

    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    @Override
    public void transcribe(Task task, Path audioPath, Path outputDir, String language) throws Exception {
        if (audioPath == null || !Files.exists(audioPath)) {
            throw new IllegalArgumentException("音频文件不存在：" + audioPath);
        }
        Files.createDirectories(outputDir);

        Path asrFile = outputDir.resolve("asr.json");
        if (Files.exists(asrFile)) {
            log.info("ASR 结果已存在，跳过：{}", asrFile);
            return;
        }

        Path outputBase = outputDir.resolve("asr");
        String model = settingsService.getProviderConfig(WHISPER_CPP, AppProperties.Asr.WhisperCpp.class).getModel();

        List<String> command = new ArrayList<>();
        command.add("whisper-cli");
        command.add("-m");
        command.add(model);
        command.add("-f");
        command.add(audioPath.toString());
        command.add("-oj");
        command.add("-of");
        command.add(outputBase.toString());
        if (language != null && !language.isBlank()) {
            command.add("-l");
            command.add(language);
        }

        log.info("执行 whisper.cpp 识别：task={}, audio={}", task.getId(), audioPath);
        try {
            CommandRunner.run(Command.builder().add(command).timeout(TIMEOUT_MS).workDir(outputDir).build());
        } catch (RuntimeException e) {
            throw new RuntimeException("whisper.cpp 识别失败（请确认 whisper-cli 已安装）： " + e.getMessage(), e);
        }

        // whisper.cpp 输出 JSON 文件：{outputBase}.json
        Path whisperJson = Path.of(outputBase + ".json");
        if (!Files.exists(whisperJson)) {
            throw new RuntimeException("whisper.cpp 输出文件不存在：" + whisperJson);
        }

        JsonNode whisperRoot = objectMapper.readTree(Files.readString(whisperJson));
        ObjectNode result = convertToStandardFormat(whisperRoot, audioPath);
        Files.writeString(asrFile, objectMapper.writeValueAsString(result));
        log.info("ASR 完成：task={}, file={}", task.getId(), asrFile);
    }

    /**
     * 将 whisper.cpp 输出格式转换为内部标准格式。
     * whisper.cpp 的 JSON 结构：{ "transcription": [ { "timestamps": { "from": "00:00:00,000", "to": "00:00:03,000" }, "offsets": { "from": 0, "to": 3000 }, "text": " ..." } ] }
     */
    private ObjectNode convertToStandardFormat(JsonNode whisperRoot, Path audioPath) {
        ObjectNode result = objectMapper.createObjectNode();
        ObjectNode audioInfo = objectMapper.createObjectNode();
        audioInfo.put("source", audioPath.toString());
        result.set("audio_info", audioInfo);

        ObjectNode resultObj = objectMapper.createObjectNode();
        StringBuilder fullText = new StringBuilder();
        ArrayNode utterances = objectMapper.createArrayNode();

        JsonNode transcription = whisperRoot.path("transcription");
        if (transcription.isArray()) {
            for (JsonNode seg : transcription) {
                JsonNode offsets = seg.path("offsets");
                long startMs = offsets.path("from").asLong(0);
                long endMs = offsets.path("to").asLong(0);
                String text = seg.path("text").asText("").trim();

                ObjectNode utterance = objectMapper.createObjectNode();
                utterance.put("text", text);
                utterance.put("start_time", startMs);
                utterance.put("end_time", endMs);
                utterance.put("speaker", "1");
                utterance.set("words", objectMapper.createArrayNode());
                utterances.add(utterance);

                if (!text.isEmpty()) {
                    if (fullText.length() > 0) {
                        fullText.append(" ");
                    }
                    fullText.append(text);
                }
            }
        }

        resultObj.put("text", fullText.toString());
        resultObj.set("utterances", utterances);
        result.set("result", resultObj);
        return result;
    }
}

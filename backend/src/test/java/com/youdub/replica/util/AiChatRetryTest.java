package com.youdub.replica.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiChatRetry} 的单元测试。
 * 重点覆盖回归：合法 JSON（内嵌转写文本含拒绝短语）不得被误判为"拒绝回答"。
 */
class AiChatRetryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    /* ────────── isRefusal：纯单元测试 ────────── */

    @Test
    void isRefusal_nullOrBlank_false() {
        assertFalse(AiChatRetry.isRefusal(null));
        assertFalse(AiChatRetry.isRefusal(""));
        assertFalse(AiChatRetry.isRefusal("   "));
    }

    @Test
    void isRefusal_englishProseRefusal_true() {
        assertTrue(AiChatRetry.isRefusal("I'm sorry, I cannot answer that question."));
        assertTrue(AiChatRetry.isRefusal("I apologize, but I cannot provide the corrections."));
    }

    @Test
    void isRefusal_chineseProseRefusal_true() {
        assertTrue(AiChatRetry.isRefusal("很抱歉，我无法回答这个问题。"));
        assertTrue(AiChatRetry.isRefusal("无法提供所需的修正内容。"));
    }

    @Test
    void isRefusal_normalProse_false() {
        assertFalse(AiChatRetry.isRefusal("The corrections are ready."));
    }

    /** 回归：合法 JSON 内嵌的转写文本含 "I'm sorry"，不得误判为拒绝。 */
    @Test
    void isRefusal_validJsonWithRefusalPhraseInText_false() {
        String content = """
                {"corrections":[{"id":339,"text":"It says here, doesn't-- yeah, it just mentions chunks."},{\
                "id":340,"text":"I'm sorry but the code does compile."}]}""";
        assertFalse(AiChatRetry.isRefusal(content));
    }

    /** 回归：JSON 数组开头的结构化输出不得误判为拒绝。 */
    @Test
    void isRefusal_structuredArray_false() {
        assertFalse(AiChatRetry.isRefusal("[{\"id\":1,\"text\":\"cannot provide details\"}]"));
    }

    /** JSON 感知重载：能解析的 JSON 即使含拒绝短语也返回 false。 */
    @Test
    void isRefusal_withMapper_validJson_false() {
        String content = "{\"corrections\":[{\"id\":1,\"text\":\"I cannot answer that either.\"}]}";
        assertFalse(AiChatRetry.isRefusal(content, MAPPER));
    }

    /** JSON 感知重载：解析失败（散文）时仍按短语匹配。 */
    @Test
    void isRefusal_withMapper_proseRefusal_true() {
        assertTrue(AiChatRetry.isRefusal("I'm sorry, I cannot answer that.", MAPPER));
        assertFalse(AiChatRetry.isRefusal("Here are the corrections.", MAPPER));
    }

    /* ────────── executeChat：MockWebServer 集成 ────────── */

    private static final AiChatRetry.RetryConfig NO_RETRY = AiChatRetry.RetryConfig.builder().maxAttempts(1).build();

    private String executeChatAgainst(String responseBody) throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody));

        Request request = new Request.Builder()
                .url(server.url("/v1/chat/completions"))
                .post(RequestBody.create("{}".getBytes(), MediaType.parse("application/json")))
                .build();
        OkHttpClient client = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        return AiChatRetry.executeChat(client, request, NO_RETRY, MAPPER);
    }

    /** 回归：executeChat 对含拒绝短语文本的合法 JSON 不得判为拒绝。 */
    @Test
    void executeChat_validJsonContentWithRefusalPhrase_returnsContent() throws Exception {
        String content = "{\"corrections\":[{\"id\":339,\"text\":\"I'm sorry but the code does compile.\"}]}";
        String body = "{\"choices\":[{\"message\":{\"content\":\"" + content.replace("\"", "\\\"") + "\"}}]}";

        String result = executeChatAgainst(body);

        assertEquals(content, result);
    }

    /** finish_reason=length（输出被 max_tokens 截断）应报可重试的截断错误。 */
    @Test
    void executeChat_finishReasonLength_reportsTruncation() throws Exception {
        String body = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\\\"corrections\\\":[]}\"}}]}";

        RuntimeException ex = assertThrows(RuntimeException.class, () -> executeChatAgainst(body));

        assertTrue(ex.getCause().getMessage().contains("截断"));
    }

    /** 散文式拒绝回答应报"拒绝回答"。 */
    @Test
    void executeChat_proseRefusal_reportsRefusal() throws Exception {
        String body = "{\"choices\":[{\"message\":{\"content\":\"I'm sorry, I cannot answer that.\"}}]}";

        RuntimeException ex = assertThrows(RuntimeException.class, () -> executeChatAgainst(body));

        assertTrue(ex.getCause().getMessage().contains("拒绝回答"));
    }
}

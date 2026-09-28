package com.travelagent.client.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelagent.exception.AgentException;
import com.travelagent.util.JsonUtil;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebSearchClient Tests")
class WebSearchClientTest {

    @Mock private OkHttpClient okHttpClient;
    @Mock private Call call;

    @InjectMocks
    private WebSearchClient client;

    @BeforeEach
    void setUp() {
        JsonUtil jsonUtil = new JsonUtil();
        ReflectionTestUtils.setField(jsonUtil, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(client, "jsonUtil", jsonUtil);
        ReflectionTestUtils.setField(client, "endpoint", "https://search.example.test/query");
        ReflectionTestUtils.setField(client, "apiKey", "WEB_KEY");
        lenient().when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
    }

    @Test
    @DisplayName("search sends query and parses result list")
    void search_sendsQueryAndParsesResults() throws Exception {
        when(call.execute()).thenReturn(response(200, """
                {"results":[{"title":"官方开放时间","url":"https://example.gov.cn","snippet":"开放"}]}
                """));

        List<Map<String, Object>> results = client.search(WebSearchQuery.builder()
                .city("北京")
                .attraction("故宫")
                .infoType("开放时间")
                .topK(2)
                .build());

        assertThat(results).hasSize(1);
        assertThat(results.get(0)).containsEntry("title", "官方开放时间");
        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
        verify(okHttpClient).newCall(requestCaptor.capture());
        assertThat(requestCaptor.getValue().url().queryParameter("q")).contains("北京").contains("故宫").contains("开放时间");
        assertThat(requestCaptor.getValue().header("Authorization")).isEqualTo("Bearer WEB_KEY");
    }

    @Test
    @DisplayName("search fails when endpoint is missing")
    void search_missingEndpointThrows() {
        ReflectionTestUtils.setField(client, "endpoint", "");

        assertThatThrownBy(() -> client.search(WebSearchQuery.builder().queryText("故宫").topK(1).build()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("web-search.endpoint");
    }

    private Response response(int code, String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://search.example.test/query").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(ResponseBody.create(body, okhttp3.MediaType.get("application/json")))
                .build();
    }
}

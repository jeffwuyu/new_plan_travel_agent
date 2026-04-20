package com.travelagent.client.dashvector;

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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * 中文注释：测试类，验证 DashVectorClient 的 upsert、search 和 delete 操作行为。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashVectorClient Tests")
class DashVectorClientTest {

    @Mock private OkHttpClient okHttpClient;
    @Mock private Call call;

    @InjectMocks private DashVectorClient client;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(client, "apiKey",     "DASHVECTOR_KEY_PLACEHOLDER");
        ReflectionTestUtils.setField(client, "endpoint",   "https://vrs-cn-test.dashvector.cn");
        ReflectionTestUtils.setField(client, "collection", "travel-knowledge");
        lenient().when(okHttpClient.newCall(any(Request.class))).thenReturn(call);
    }

    private Response buildResponse(int code, String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://vrs-cn-test.dashvector.cn/v1/test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(ResponseBody.create(body, okhttp3.MediaType.get("application/json")))
                .build();
    }

    @Test
    @DisplayName("upsert: sends POST request with vector and fields in JSON body")
    void upsert_sendsCorrectRequest() throws Exception {
        when(call.execute()).thenReturn(buildResponse(200, "{\"code\":0}"));

        float[] vec = new float[]{0.1f, 0.2f, 0.3f};
        client.upsert("doc1-chunk0", vec, Map.of("region", "西安市", "chunkText", "兵马俑"));

        verify(call).execute();
    }

    @Test
    @DisplayName("upsert: throws RuntimeException on HTTP error response")
    void upsert_throwsOnHttpError() throws Exception {
        when(call.execute()).thenReturn(buildResponse(400, "{\"message\":\"bad request\"}"));

        assertThatThrownBy(() -> client.upsert("id1", new float[]{0.1f}, Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("upsert failed");
    }

    @Test
    @DisplayName("search: parses output array and returns DashVectorResult list")
    void search_parsesResponseCorrectly() throws Exception {
        String responseJson = """
                {
                  "output": [
                    {"id":"doc1-chunk0","score":0.95,"fields":{"region":"西安市","chunkText":"兵马俑简介"}},
                    {"id":"doc1-chunk1","score":0.88,"fields":{"region":"西安市","chunkText":"华清池历史"}}
                  ]
                }
                """;
        when(call.execute()).thenReturn(buildResponse(200, responseJson));

        List<DashVectorClient.DashVectorResult> results =
                client.search(new float[]{0.1f, 0.2f}, 5, "西安市");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).id).isEqualTo("doc1-chunk0");
        assertThat(results.get(0).score).isCloseTo(0.95f, within(0.001f));
        assertThat(results.get(0).fields.get("chunkText")).isEqualTo("兵马俑简介");
    }

    @Test
    @DisplayName("search: null regionFilter sends request without filter field")
    void search_nullRegionFilter_noFilterInRequest() throws Exception {
        String responseJson = "{\"output\":[]}";
        when(call.execute()).thenReturn(buildResponse(200, responseJson));

        ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);

        List<DashVectorClient.DashVectorResult> results =
                client.search(new float[]{0.1f}, 3, null);

        assertThat(results).isEmpty();
        verify(call).execute();
    }

    @Test
    @DisplayName("search: throws RuntimeException on HTTP 500")
    void search_throwsOnServerError() throws Exception {
        when(call.execute()).thenReturn(buildResponse(500, "Internal Server Error"));

        assertThatThrownBy(() -> client.search(new float[]{0.1f}, 5, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("search failed");
    }

    @Test
    @DisplayName("delete: sends DELETE request with ids array")
    void delete_sendsDeleteRequest() throws Exception {
        when(call.execute()).thenReturn(buildResponse(200, "{}"));

        client.delete(List.of("doc1-chunk0", "doc1-chunk1"));

        verify(call).execute();
    }

    @Test
    @DisplayName("delete: does nothing for empty id list")
    void delete_noopForEmptyList() throws Exception {
        client.delete(List.of());
        verify(okHttpClient, never()).newCall(any());
    }
}

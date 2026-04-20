package com.travelagent.client.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 中文注释：测试类，验证 OssClient 的上传、下载、删除行为。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OssClient Tests")
class OssClientTest {

    @Mock private OSS ossClient;

    @InjectMocks private OssClient client;

    private static final String BUCKET = "travel-agent-docs";
    private static final String KEY    = "rag/documents/test.txt";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(client, "ossClient",    ossClient);
        ReflectionTestUtils.setField(client, "bucketName",   BUCKET);
        ReflectionTestUtils.setField(client, "endpoint",     "https://oss-cn-hangzhou.aliyuncs.com");
        ReflectionTestUtils.setField(client, "accessKeyId",  "AK_PLACEHOLDER");
        ReflectionTestUtils.setField(client, "accessKeySecret", "SK_PLACEHOLDER");
    }

    @Test
    @DisplayName("uploadDocument: calls putObject with correct bucket and key")
    void uploadDocument_callsPutObjectWithCorrectArgs() {
        byte[] content = "hello RAG".getBytes();
        client.uploadDocument(KEY, content, "text/plain");

        ArgumentCaptor<String>  bucketCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String>  keyCaptor    = ArgumentCaptor.forClass(String.class);
        verify(ossClient).putObject(bucketCaptor.capture(), keyCaptor.capture(),
                any(ByteArrayInputStream.class), any(ObjectMetadata.class));

        assertThat(bucketCaptor.getValue()).isEqualTo(BUCKET);
        assertThat(keyCaptor.getValue()).isEqualTo(KEY);
    }

    @Test
    @DisplayName("downloadDocument: returns bytes from OSSObject stream")
    void downloadDocument_returnsBytesFromStream() throws Exception {
        byte[] expected = "file content".getBytes();
        OSSObject ossObject = mock(OSSObject.class);
        when(ossObject.getObjectContent()).thenReturn(new ByteArrayInputStream(expected));
        when(ossClient.getObject(BUCKET, KEY)).thenReturn(ossObject);

        byte[] result = client.downloadDocument(KEY);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    @DisplayName("deleteDocument: delegates to ossClient.deleteObject")
    void deleteDocument_delegatesToOssClient() {
        client.deleteDocument(KEY);
        verify(ossClient).deleteObject(BUCKET, KEY);
    }

    @Test
    @DisplayName("downloadDocument: wraps IOException as RuntimeException")
    void downloadDocument_wrapsIOException() throws Exception {
        OSSObject ossObject = mock(OSSObject.class);
        var failingStream = new java.io.InputStream() {
            @Override public int read() throws java.io.IOException { throw new java.io.IOException("stream error"); }
        };
        when(ossObject.getObjectContent()).thenReturn(failingStream);
        when(ossClient.getObject(BUCKET, KEY)).thenReturn(ossObject);

        assertThatThrownBy(() -> client.downloadDocument(KEY))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to read OSS object");
    }
}

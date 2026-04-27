package com.travelagent.client.oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.OSSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 中文注释：客户端类，封装阿里云 OSS SDK，提供 RAG 文档的上传、下载、删除操作。
 */
@Component
public class OssClient {

    private static final Logger log = LoggerFactory.getLogger(OssClient.class);

    @Value("${oss.endpoint}")
    private String endpoint;

    @Value("${oss.access-key-id}")
    private String accessKeyId;

    @Value("${oss.access-key-secret}")
    private String accessKeySecret;

    @Value("${oss.bucket-name}")
    private String bucketName;

    private OSS ossClient;

    /**
     * 处理init。
     */
    @PostConstruct
    public void init() {
        ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        log.info("OSS client initialized, bucket={}", bucketName);
    }

    /**
     * 处理destroy。
     */
    @PreDestroy
    public void destroy() {
        if (ossClient != null) {
            ossClient.shutdown();
        }
    }

    /**
     * 处理uploadDocument。
     * @param ossKey o ss Ke y 参数
     * @param content 内容
     * @param contentType c on te nt Ty pe 参数
     */
    public void uploadDocument(String ossKey, byte[] content, String contentType) {
        log.debug("Uploading to OSS: key={}, size={}", ossKey, content.length);
        com.aliyun.oss.model.ObjectMetadata meta = new com.aliyun.oss.model.ObjectMetadata();
        meta.setContentType(contentType);
        meta.setContentLength(content.length);
        ossClient.putObject(bucketName, ossKey, new ByteArrayInputStream(content), meta);
        log.info("OSS upload complete: key={}", ossKey);
    }

    /**
     * 处理downloadDocument。
     * @param ossKey o ss Ke y 参数
     * @return 返回处理结果。
     */
    public byte[] downloadDocument(String ossKey) {
        log.debug("Downloading from OSS: key={}", ossKey);
        OSSObject obj = ossClient.getObject(bucketName, ossKey);
        try (InputStream is = obj.getObjectContent()) {
            byte[] bytes = is.readAllBytes();
            log.info("OSS download complete: key={}, size={}", ossKey, bytes.length);
            return bytes;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read OSS object: " + ossKey, e);
        }
    }

    /**
     * 删除document。
     * @param ossKey o ss Ke y 参数
     */
    public void deleteDocument(String ossKey) {
        log.debug("Deleting from OSS: key={}", ossKey);
        ossClient.deleteObject(bucketName, ossKey);
        log.info("OSS delete complete: key={}", ossKey);
    }
}

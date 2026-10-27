package com.marketpix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class R2ClientTests {

    /** The "GET Object" example from AWS's Signature Version 4 documentation, with its region and service. */
    @Test
    void signsLikeTheDocumentedSignatureV4Example() {
        TreeMap<String, String> headers = new TreeMap<>(Map.of(
                "host", "examplebucket.s3.amazonaws.com",
                "range", "bytes=0-9",
                "x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "x-amz-date", "20130524T000000Z"));

        String authorization = R2Client.authorization(
                "GET", "/test.txt", "", headers, headers.get("x-amz-content-sha256"),
                "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1", "20130524T000000Z");

        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", authorization);
    }

    @Test
    void putsAndGetsObjectsInTheBucketWithASignedRequest() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = "saved".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try {
            R2Client client = client("http://127.0.0.1:" + server.getAddress().getPort(), "key-id", "secret");

            client.put("261340102026J2378/record.json", "{}".getBytes(StandardCharsets.UTF_8), "application/json");

            assertEquals("PUT", method.get());
            assertEquals("/marketpix-images/261340102026J2378/record.json", path.get());
            assertEquals("{}", body.get());
            assertTrue(authorization.get().startsWith("AWS4-HMAC-SHA256 Credential=key-id/"), authorization.get());
            assertEquals("saved", new String(client.get("261340102026J2378/record.json").orElseThrow()));
            assertEquals("GET", method.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void listsTopLevelFoldersAcrossContinuationPages() throws Exception {
        List<String> queries = new ArrayList<>();
        AtomicReference<String> path = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            String query = exchange.getRequestURI().getRawQuery();
            queries.add(query);
            String reply = query.contains("continuation-token")
                    ? "<ListBucketResult><IsTruncated>false</IsTruncated>"
                            + "<CommonPrefixes><Prefix>270915102026B0001/</Prefix></CommonPrefixes></ListBucketResult>"
                    : "<ListBucketResult><IsTruncated>true</IsTruncated>"
                            + "<NextContinuationToken>next/page=</NextContinuationToken>"
                            + "<CommonPrefixes><Prefix>261340102026J2378/</Prefix></CommonPrefixes></ListBucketResult>";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            R2Client client = client("http://127.0.0.1:" + server.getAddress().getPort(), "key-id", "secret");

            assertEquals(List.of("261340102026J2378", "270915102026B0001"), client.listFolders());
            assertEquals("/marketpix-images", path.get());
            assertEquals("delimiter=%2F&list-type=2", queries.get(0));
            assertEquals("continuation-token=next%2Fpage%3D&delimiter=%2F&list-type=2", queries.get(1));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void isOffUntilTheKeysAreSet() {
        assertFalse(client("http://127.0.0.1:1", "", "").isConfigured());
        assertTrue(client("http://127.0.0.1:1", "key-id", "secret").isConfigured());
    }

    @Test
    void imageRecordTagsAreTitleCasedAndDeduplicated() throws Exception {
        var tags = new ObjectMapper().readTree("{\"tags\":[\"chair\",\" RED \",\"Red\",\"american  oak\",\"\"]}");

        assertEquals(List.of("Chair", "Red", "American Oak"), ImageRecordService.normalize(tags.path("tags")));
    }

    private static R2Client client(String endpoint, String accessKeyId, String secret) {
        ApiKeyConfiguration keys = mock(ApiKeyConfiguration.class);
        when(keys.getR2AccessKeyId()).thenReturn(accessKeyId);
        when(keys.getR2SecretAccessKey()).thenReturn(secret);
        return new R2Client(keys, "", "marketpix-images", endpoint);
    }
}

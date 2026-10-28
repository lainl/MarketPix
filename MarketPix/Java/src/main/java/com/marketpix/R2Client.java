package com.marketpix;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Minimal Cloudflare R2 client: signed PUT and GET of single objects, and a listing of the bucket's
 * top-level folders, over R2's S3-compatible API.
 */
@Component
public class R2Client {

    private static final String REGION = "auto";
    private static final String SERVICE = "s3";
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String bucket;
    private final URI endpoint;

    public R2Client(
            ApiKeyConfiguration apiKeys,
            @Value("${r2.account-id}") String accountId,
            @Value("${r2.bucket}") String bucket,
            @Value("${r2.endpoint}") String endpoint) {
        this.accessKeyId = apiKeys.getR2AccessKeyId();
        this.secretAccessKey = apiKeys.getR2SecretAccessKey();
        this.bucket = bucket.trim();
        String base = endpoint.isBlank() && !accountId.isBlank()
                ? "https://" + accountId.trim() + ".r2.cloudflarestorage.com"
                : endpoint.trim();
        this.endpoint = base.isEmpty() ? null : URI.create(base);
    }

    public boolean isConfigured() {
        return endpoint != null && !bucket.isEmpty() && !accessKeyId.isBlank() && !secretAccessKey.isBlank();
    }

    public void put(String key, byte[] body, String contentType) {
        HttpResponse<byte[]> response = send("PUT", key, body, contentType);
        if (response.statusCode() / 100 != 2) {
            throw failure("saving " + key, response);
        }
    }

    /** Returns the object, or empty when it does not exist. */
    public Optional<byte[]> get(String key) {
        HttpResponse<byte[]> response = send("GET", key, new byte[0], null);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw failure("reading " + key, response);
        }
        return Optional.of(response.body());
    }

    /** Deletes one object; deleting one that does not exist also succeeds. */
    public void delete(String key) {
        HttpResponse<byte[]> response = send("DELETE", key, new byte[0], null);
        if (response.statusCode() / 100 != 2 && response.statusCode() != 404) {
            throw failure("deleting " + key, response);
        }
    }

    /**
     * Lists the "folders" directly under the bucket root, such as {@code 261340102026J2378/}, without the
     * trailing slash. Follows continuation tokens until the whole bucket is listed.
     */
    public List<String> listFolders() {
        List<String> folders = new ArrayList<>();
        String continuation = null;
        do {
            SortedMap<String, String> query = new TreeMap<>();
            query.put("delimiter", "/");
            query.put("list-type", "2");
            if (continuation != null) {
                query.put("continuation-token", continuation);
            }
            HttpResponse<byte[]> response = send("GET", "", canonicalQuery(query), new byte[0], null);
            if (response.statusCode() / 100 != 2) {
                throw failure("listing the bucket", response);
            }
            Document listing = parseXml(response.body());
            NodeList prefixes = listing.getElementsByTagName("CommonPrefixes");
            for (int index = 0; index < prefixes.getLength(); index++) {
                String prefix = childText((Element) prefixes.item(index), "Prefix");
                if (prefix != null && prefix.endsWith("/")) {
                    folders.add(prefix.substring(0, prefix.length() - 1));
                }
            }
            boolean truncated = "true".equals(childText(listing.getDocumentElement(), "IsTruncated"));
            continuation = truncated ? childText(listing.getDocumentElement(), "NextContinuationToken") : null;
        } while (continuation != null && !continuation.isEmpty());
        return folders;
    }

    private HttpResponse<byte[]> send(String method, String key, byte[] body, String contentType) {
        return send(method, key, "", body, contentType);
    }

    private HttpResponse<byte[]> send(String method, String key, String query, byte[] body, String contentType) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cloud image storage is not configured.");
        }
        String canonicalUri = key.isEmpty() ? "/" + encode(bucket) : "/" + encode(bucket) + "/" + encodePath(key);
        String host = endpoint.getPort() < 0 ? endpoint.getHost() : endpoint.getHost() + ":" + endpoint.getPort();
        String payloadHash = sha256Hex(body);
        String amzDate = AMZ_DATE.format(ZonedDateTime.now(ZoneOffset.UTC));

        SortedMap<String, String> headers = new TreeMap<>();
        headers.put("host", host);
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        String authorization = authorization(
                method, canonicalUri, query, headers, payloadHash, accessKeyId, secretAccessKey, REGION, amzDate);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                        endpoint.getScheme() + "://" + host + canonicalUri + (query.isEmpty() ? "" : "?" + query)))
                .timeout(TIMEOUT)
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization);
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        request.method(method, body.length == 0 && !method.equals("PUT")
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Could not reach Cloudflare R2 storage.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Cloudflare R2 storage request was interrupted.", exception);
        }
    }

    private static ResponseStatusException failure(String action, HttpResponse<byte[]> response) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Cloudflare R2 rejected " + action + " (HTTP " + response.statusCode() + ").");
    }

    /** AWS Signature Version 4 {@code Authorization} header value; {@code headers} are lower-case names. */
    static String authorization(
            String method,
            String canonicalUri,
            String canonicalQuery,
            SortedMap<String, String> headers,
            String payloadHash,
            String accessKeyId,
            String secretAccessKey,
            String region,
            String amzDate) {
        String date = amzDate.substring(0, 8);
        StringBuilder canonicalHeaders = new StringBuilder();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            canonicalHeaders.append(header.getKey()).append(':').append(header.getValue().trim()).append('\n');
        }
        String signedHeaders = String.join(";", headers.keySet());
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = date + "/" + region + "/" + SERVICE + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] key = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, SERVICE);
        key = hmac(key, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(key, stringToSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable.", exception);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    /** Signature V4 canonical query string; the same text is sent on the request URL. */
    static String canonicalQuery(SortedMap<String, String> parameters) {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(encode(parameter.getKey())).append('=').append(encode(parameter.getValue()));
        }
        return query.toString();
    }

    private static Document parseXml(byte[] body) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Cloudflare R2 sent an unreadable bucket listing.", exception);
        }
    }

    private static String childText(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && name.equals(element.getTagName())) {
                return element.getTextContent();
            }
        }
        return null;
    }

    private static String encodePath(String key) {
        String[] segments = key.split("/", -1);
        for (int index = 0; index < segments.length; index++) {
            segments[index] = encode(segments[index]);
        }
        return String.join("/", segments);
    }

    private static String encode(String value) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return encoded.toString();
    }
}

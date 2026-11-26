/*
 * Copyright 2014-2026 Andrew Gaul <andrew@gaul.org>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gaul.s3proxy.oci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.google.common.base.Splitter;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.gaul.s3proxy.S3Proxy;
import org.gaul.s3proxy.auth.AuthenticationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.GetBucketLocationRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** A synthetic native IAM service verifies the actual per-user RSA signer. */
public final class OciNativeAuthorizationTest {
    @TempDir
    private Path directory;

    private final AtomicBoolean readerActive = new AtomicBoolean(true);
    private final AtomicBoolean writerKeyActive = new AtomicBoolean(true);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final List<OciNativeBlobStore> stores = new ArrayList<>();
    private HttpServer nativeServer;
    private S3Proxy proxy;
    private S3Client reader;
    private S3Client writer;
    private Path readerFile;
    private Properties readerProperties;

    @BeforeEach
    public void setUp() throws Exception {
        var readerKeys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var writerKeys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var principals = Map.of("reader", readerKeys, "writer", writerKeys);
        nativeServer = HttpServer.create(new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        nativeServer.createContext("/oci/", exchange -> {
            try {
                calls.incrementAndGet();
                String authorization = exchange.getRequestHeaders()
                        .getFirst("Authorization");
                String keyId = parameter(authorization, "keyId");
                String user = keyId.contains("/reader/") ? "reader" : "writer";
                assertThat(keyId).isEqualTo("tenant/" + user + "/fingerprint");
                verifySignature(exchange, authorization, principals.get(user));
                String path = exchange.getRequestURI().getRawPath();
                String method = exchange.getRequestMethod();
                boolean reading = method.equals("GET") || method.equals("HEAD");
                boolean allowed = user.equals("reader") ?
                        readerActive.get() && reading &&
                                !path.contains("/b/protected") :
                        writerKeyActive.get();
                if (!allowed) {
                    respond(exchange, 403,
                            "{\"code\":\"NotAuthorized\",\"message\":\"Denied\"}");
                } else if (method.equals("HEAD") || method.equals("DELETE")) {
                    exchange.sendResponseHeaders(method.equals("HEAD") ? 200 : 204,
                            -1);
                } else if (path.endsWith("/b/shared")) {
                    respond(exchange, 200,
                            "{\"name\":\"shared\",\"publicAccessType\":\"NoPublicAccess\"}");
                } else if (path.endsWith("/u") && method.equals("POST")) {
                    exchange.getRequestBody().readAllBytes();
                    respond(exchange, 200,
                            "{\"uploadId\":\"native-upload\",\"object\":\"key\"}");
                } else if (path.contains("/o/")) {
                    exchange.getResponseHeaders().set("ETag", "native-etag");
                    if (method.equals("PUT")) {
                        exchange.getRequestBody().readAllBytes();
                        exchange.sendResponseHeaders(200, -1);
                    } else {
                        respond(exchange, 200, "native object for " + user);
                    }
                } else {
                    throw new AssertionError("Unexpected native operation");
                }
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        nativeServer.start();
        var users = new OciNativeUsers();
        for (String user : List.of("reader", "writer")) {
            var keys = principals.get(user);
            byte[] pem = ("-----BEGIN PRIVATE KEY-----\n" +
                    Base64.getMimeEncoder(64, new byte[] {'\n'})
                            .encodeToString(keys.getPrivate().getEncoded()) +
                    "\n-----END PRIVATE KEY-----\n")
                    .getBytes(StandardCharsets.US_ASCII);
            var auth = SimpleAuthenticationDetailsProvider.builder()
                    .tenantId("tenant").userId(user).fingerprint("fingerprint")
                    .privateKeySupplier(() -> new ByteArrayInputStream(pem))
                    .build();
            var client = ObjectStorageClient.builder().endpoint(
                    "http://127.0.0.1:" + nativeServer.getAddress().getPort() +
                            "/oci").build(auth);
            var store = new OciNativeBlobStore(client, "ns", "compartment");
            stores.add(store);
            var properties = new Properties();
            properties.setProperty("jclouds.provider", "oci-native");
            properties.setProperty("s3proxy.authorization", "aws-v4");
            properties.setProperty("s3proxy.identity", user + "-access");
            properties.setProperty("s3proxy.credential", user + "-secret");
            properties.setProperty("s3proxy.oci.user-id", user);
            Path file = directory.resolve(user + ".properties");
            save(properties, file);
            users.add(file, properties, store, user);
            if (user.equals("reader")) {
                readerFile = file;
                readerProperties = properties;
            }
        }
        proxy = S3Proxy.builder().blobStore(stores.get(0)).stopTimeout(0)
                .endpoint(URI.create("http://127.0.0.1:0"))
                .awsAuthentication(AuthenticationType.AWS_V4,
                        "unused-default", "unused-secret").build();
        proxy.setBlobStoreLocator(users);
        proxy.start();
        reader = s3("reader-access", "reader-secret");
        writer = s3("writer-access", "writer-secret");
    }

    private S3Client s3(String key, String secret) {
        return S3Client.builder().endpointOverride(URI.create(
                "http://127.0.0.1:" + proxy.getPort()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(key, secret)))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false).build()).build();
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (reader != null) {
            reader.close();
        }
        if (writer != null) {
            writer.close();
        }
        if (proxy != null) {
            proxy.stop();
        }
        for (var store : stores) {
            store.close();
        }
        if (nativeServer != null) {
            nativeServer.stop(0);
        }
        assertThat(failure.get()).isNull();
    }

    @Test
    public void nativeIamSeparatesReaderWriterAndBuckets() {
        assertThat(reader.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("shared").key("key").build()).asUtf8String())
                .isEqualTo("native object for reader");
        writer.putObject(PutObjectRequest.builder().bucket("shared").key("key")
                .build(), RequestBody.fromString("allowed"));
        assertDenied(() -> reader.putObject(PutObjectRequest.builder()
                .bucket("shared").key("key").build(),
                RequestBody.fromString("forbidden")));
        assertDenied(() -> reader.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("protected").key("key").build()));
        assertDenied(() -> reader.deleteObject(
                software.amazon.awssdk.services.s3.model.DeleteObjectRequest
                        .builder().bucket("shared").key("key").build()));
        var upload = writer.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket("shared")
                        .key("key").build());
        assertDenied(() -> reader.abortMultipartUpload(
                AbortMultipartUploadRequest.builder().bucket("shared")
                        .key("key").uploadId(upload.uploadId()).build()));
        writer.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                .bucket("shared").key("key").uploadId(upload.uploadId())
                .build());
    }

    @Test
    public void nativePermissionAndSigningKeyRevocationAreNotCached() {
        reader.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("shared").key("key").build());
        readerActive.set(false);
        assertDenied(() -> reader.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("shared").key("key").build()));
        assertDenied(() -> reader.getBucketLocation(
                GetBucketLocationRequest.builder().bucket("shared").build()));
        writerKeyActive.set(false);
        assertDenied(() -> writer.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("shared").key("key").build()));
    }

    @Test
    public void invalidAndRevokedGatewayKeysNeverReachNativeApi()
            throws Exception {
        int before = calls.get();
        try (var forged = s3("reader-access", "writer-secret");
             var unknown = s3("unknown", "writer-secret")) {
            assertDenied(() -> forged.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket("shared").key("key").build()));
            assertDenied(() -> unknown.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket("shared").key("key").build()));
        }
        readerProperties.setProperty("s3proxy.oci.enabled", "false");
        save(readerProperties, readerFile);
        assertDenied(() -> reader.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("shared").key("key").build()));
        assertThat(calls.get()).isEqualTo(before);
        var request = java.net.http.HttpRequest.newBuilder(URI.create(
                "http://127.0.0.1:" + proxy.getPort() + "/shared/key"))
                .GET().build();
        var http = java.net.http.HttpClient.newHttpClient();
        assertThat(http.send(request,
                java.net.http.HttpResponse.BodyHandlers.discarding())
                .statusCode()).isEqualTo(403);
        assertThat(calls.get()).isEqualTo(before);
        readerProperties.setProperty("s3proxy.oci.enabled", "true");
        readerProperties.setProperty("s3proxy.credential", "rotated-secret");
        save(readerProperties, readerFile);
        assertDenied(() -> reader.getObjectAsBytes(GetObjectRequest.builder()
                .bucket("shared").key("key").build()));
        assertThat(calls.get()).isEqualTo(before);
        try (var rotated = s3("reader-access", "rotated-secret")) {
            assertThat(rotated.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket("shared").key("key").build()).asUtf8String())
                    .isEqualTo("native object for reader");
        }
    }

    private static void assertDenied(org.assertj.core.api.ThrowableAssert
            .ThrowingCallable operation) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(S3Exception.class,
                error -> assertThat(error.statusCode()).isEqualTo(403));
    }

    private static void save(Properties properties, Path file)
            throws IOException {
        try (var stream = Files.newOutputStream(file)) {
            properties.store(stream, null);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void verifySignature(HttpExchange exchange,
            String authorization, KeyPair keys) throws Exception {
        var signed = new StringBuilder();
        for (String name : Splitter.on(' ').split(
                parameter(authorization, "headers"))) {
            if (!signed.isEmpty()) {
                signed.append('\n');
            }
            signed.append(name).append(": ");
            if (name.equals("(request-target)")) {
                signed.append(exchange.getRequestMethod().toLowerCase(
                        java.util.Locale.ROOT)).append(' ')
                        .append(exchange.getRequestURI());
            } else {
                signed.append(exchange.getRequestHeaders().getFirst(name));
            }
        }
        var verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keys.getPublic());
        verifier.update(signed.toString().getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(
                parameter(authorization, "signature")))).isTrue();
    }

    private static String parameter(String header, String name) {
        var matcher = Pattern.compile(name + "=\"([^\"]+)\"")
                .matcher(header);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}

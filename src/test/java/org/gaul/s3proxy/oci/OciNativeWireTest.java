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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.google.common.base.Splitter;
import com.oracle.bmc.auth.SimpleAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.sun.net.httpserver.HttpServer;

import org.gaul.s3proxy.S3Proxy;
import org.gaul.s3proxy.auth.AuthenticationType;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Real AWS signing, S3 frontend, native SDK transport and RSA signing. */
public final class OciNativeWireTest {
    @Test
    public void s3PutAndGetReachSignedNativeApiUnderOciPath() throws Exception {
        var keys = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var privateKey = ("-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, new byte[] {'\n'})
                        .encodeToString(keys.getPrivate().getEncoded()) +
                "\n-----END PRIVATE KEY-----\n")
                .getBytes(StandardCharsets.US_ASCII);
        var failure = new AtomicReference<Throwable>();
        var stored = new AtomicReference<byte[]>();
        var publicBucket = new java.util.concurrent.atomic.AtomicBoolean();
        var nativeServer = HttpServer.create(
                new InetSocketAddress(InetAddress.getByAddress(
                        new byte[] {127, 0, 0, 1}), 0), 0);
        nativeServer.createContext("/oci/", exchange -> {
            try {
                String nativePath = exchange.getRequestURI().getRawPath();
                assertThat(nativePath).isIn("/oci/n/ns/b/bucket",
                        "/oci/n/ns/b/bucket/o/folder%2Fkey+space");
                String authorization = exchange.getRequestHeaders()
                        .getFirst("Authorization");
                assertThat(authorization).contains("rsa-sha256")
                        .contains("tenant/user/fingerprint");
                String signedHeaders = parameter(authorization, "headers");
                var signingText = new StringBuilder();
                for (String name : Splitter.on(' ').split(signedHeaders)) {
                    if (!signingText.isEmpty()) {
                        signingText.append('\n');
                    }
                    signingText.append(name).append(": ");
                    if ("(request-target)".equals(name)) {
                        signingText.append(exchange.getRequestMethod()
                                .toLowerCase(java.util.Locale.ROOT))
                                .append(' ').append(exchange.getRequestURI());
                    } else {
                        signingText.append(exchange.getRequestHeaders()
                                .getFirst(name));
                    }
                }
                var verifier = Signature.getInstance("SHA256withRSA");
                verifier.initVerify(keys.getPublic());
                verifier.update(signingText.toString()
                        .getBytes(StandardCharsets.UTF_8));
                assertThat(verifier.verify(Base64.getDecoder().decode(
                        parameter(authorization, "signature")))).isTrue();
                exchange.getResponseHeaders().set("ETag", "native-etag");
                exchange.getResponseHeaders().set("opc-request-id", "test");
                if (nativePath.equals("/oci/n/ns/b/bucket")) {
                    byte[] json = ("{\"name\":\"bucket\",\"publicAccessType\":\"" +
                            (publicBucket.get() ? "ObjectRead" :
                                    "NoPublicAccess") + "\"}")
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type",
                            "application/json");
                    exchange.sendResponseHeaders(200, json.length);
                    exchange.getResponseBody().write(json);
                } else if ("PUT".equals(exchange.getRequestMethod())) {
                    assertThat(exchange.getRequestHeaders()
                            .getFirst("if-none-match")).isEqualTo("*");
                    stored.set(exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(200, -1);
                } else {
                    assertThat(exchange.getRequestMethod()).isEqualTo("GET");
                    exchange.getResponseHeaders().set("Content-Type",
                            "application/octet-stream");
                    exchange.sendResponseHeaders(200, stored.get().length);
                    exchange.getResponseBody().write(stored.get());
                }
            } catch (Throwable error) {
                failure.set(error);
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        nativeServer.start();
        var auth = SimpleAuthenticationDetailsProvider.builder()
                .tenantId("tenant").userId("user").fingerprint("fingerprint")
                .privateKeySupplier(() -> new ByteArrayInputStream(privateKey))
                .build();
        var nativeClient = ObjectStorageClient.builder().endpoint(
                "http://127.0.0.1:" + nativeServer.getAddress().getPort() +
                        "/oci").build(auth);
        var store = new OciNativeBlobStore(nativeClient, "ns", "compartment");
        var proxy = S3Proxy.builder().blobStore(store).stopTimeout(0)
                .endpoint(URI.create("http://127.0.0.1:0"))
                .awsAuthentication(AuthenticationType.AWS_V2_OR_V4,
                        "s3-access", "s3-secret").build();
        try {
            proxy.start();
            try (var s3 = S3Client.builder().endpointOverride(URI.create(
                    "http://127.0.0.1:" + proxy.getPort()))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("s3-access",
                                    "s3-secret")))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .chunkedEncodingEnabled(false).build()).build()) {
                byte[] payload = "native PCA object".getBytes(
                        StandardCharsets.UTF_8);
                s3.putObject(PutObjectRequest.builder().bucket("bucket")
                        .key("folder/key+space").ifNoneMatch("*").build(),
                        RequestBody.fromBytes(payload));
                assertThat(stored.get()).isEqualTo(payload);
                assertThat(s3.getObjectAsBytes(GetObjectRequest.builder()
                        .bucket("bucket").key("folder/key+space").build())
                        .asByteArray()).isEqualTo(payload);
                publicBucket.set(true);
                assertThatThrownBy(() -> s3.putObject(PutObjectRequest.builder()
                        .bucket("bucket").key("folder/key+space").build(),
                        RequestBody.fromBytes(new byte[] {0})))
                        .isInstanceOfSatisfying(software.amazon.awssdk.services
                                .s3.model.S3Exception.class, error ->
                                assertThat(error.statusCode()).isEqualTo(501));
                assertThat(stored.get()).isEqualTo(payload);
                assertThat(failure.get()).isNull();
            }
        } catch (Exception error) {
            if (failure.get() != null) {
                throw new AssertionError("Native fixture rejected request",
                        failure.get());
            }
            throw error;
        } finally {
            proxy.stop();
            store.close();
            nativeServer.stop(0);
        }
    }

    private static String parameter(String header, String name) {
        var matcher = Pattern.compile(name + "=\"([^\"]+)\"")
                .matcher(header);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}

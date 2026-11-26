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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class OciNativeCliTest {
    @TempDir
    private Path directory;

    @Test
    public void cliReachesNativeValidationWithoutDummyBackendCredentials()
            throws Exception {
        Path config = directory.resolve("s3proxy.properties");
        Files.writeString(config, """
                jclouds.provider=pca-native
                jclouds.endpoint=https://pca.example/oci
                """);
        Path output = directory.resolve("output.txt");
        var process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java")
                        .toString(),
                "-cp", System.getProperty("java.class.path"),
                "org.gaul.s3proxy.Main", "--properties", config.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile())
                .start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isEqualTo(1);
            assertThat(Files.readString(output))
                    .contains("s3proxy.oci.namespace")
                    .doesNotContain("jclouds.identity and jclouds.credential");
        } finally {
            process.destroyForcibly();
        }
    }
    @Test
    public void nativeSigningProfileMustMatchTheBoundUser() throws Exception {
        Path key = directory.resolve("test-only-key.pem");
        var pair = java.security.KeyPairGenerator.getInstance("RSA")
                .generateKeyPair();
        Files.writeString(key, "-----BEGIN PRIVATE KEY-----\n" +
                java.util.Base64.getMimeEncoder(64, new byte[] {'\n'})
                        .encodeToString(pair.getPrivate().getEncoded()) +
                "\n-----END PRIVATE KEY-----\n");
        Path config = directory.resolve("oci-config");
        Files.writeString(config, """
                [DEFAULT]
                user=alice
                tenancy=tenant
                fingerprint=fingerprint
                region=us-phoenix-1
                key_file=%s
                """.formatted(key));
        var properties = new java.util.Properties();
        properties.setProperty("jclouds.endpoint", "https://pca.example/oci");
        properties.setProperty("s3proxy.oci.namespace", "ns");
        properties.setProperty("s3proxy.oci.compartment", "compartment");
        properties.setProperty("s3proxy.oci.config-file", config.toString());
        properties.setProperty("s3proxy.oci.user-id", "administrator");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                OciNativeBlobStore.create(properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match OCI profile user");
        properties.setProperty("s3proxy.oci.user-id", "alice");
        try (var backend = OciNativeBlobStore.create(properties)) {
            assertThat(backend.nativeUserId()).isEqualTo("alice");
        }
    }

}

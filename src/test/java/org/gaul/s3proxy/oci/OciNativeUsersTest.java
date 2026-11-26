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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.gaul.s3proxy.blobstore.BlobStore;
import org.gaul.s3proxy.nio2blob.TransientNio2BlobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public final class OciNativeUsersTest {
    @TempDir
    private Path directory;

    private static Properties configuration(String key, String user) {
        var properties = new Properties();
        properties.setProperty("jclouds.provider", "oci-native");
        properties.setProperty("jclouds.endpoint", "https://pca.example/oci");
        properties.setProperty("s3proxy.authorization", "aws-v4");
        properties.setProperty("s3proxy.identity", key);
        properties.setProperty("s3proxy.credential", "secret-for-" + key);
        properties.setProperty("s3proxy.oci.user-id", user);
        properties.setProperty("s3proxy.oci.namespace", "ns");
        properties.setProperty("s3proxy.oci.compartment", "compartment");
        properties.setProperty("s3proxy.oci.config-file", "/run/secrets/oci");
        properties.setProperty("s3proxy.oci.profile", user);
        return properties;
    }

    private Path save(Properties properties, String name) throws Exception {
        Path file = directory.resolve(name);
        try (var stream = Files.newOutputStream(file)) {
            properties.store(stream, null);
        }
        return file;
    }

    @Test
    public void eachKeySelectsOnlyItsOwnUserAndNoAnonymousFallback()
            throws Exception {
        var users = new OciNativeUsers();
        BlobStore aliceStore = new TransientNio2BlobStore();
        BlobStore bobStore = new TransientNio2BlobStore();
        var alice = configuration("alice-key", "alice");
        var bob = configuration("bob-key", "bob");
        users.add(save(alice, "alice.properties"), alice, aliceStore, "alice");
        users.add(save(bob, "bob.properties"), bob, bobStore, "bob");
        assertThat(users.locateBlobStore("alice-key", "b", "k").blobStore())
                .isSameAs(aliceStore);
        assertThat(users.locateBlobStore("bob-key", "b", "k").blobStore())
                .isSameAs(bobStore);
        assertThat(users.locateBlobStore("unknown", "b", "k")).isNull();
        assertThat(users.locateBlobStore(null, "b", "k")).isNull();
    }

    @Test
    public void rotationAndRevocationAreReadOnEveryLookup() throws Exception {
        var users = new OciNativeUsers();
        var properties = configuration("key", "user");
        Path file = save(properties, "user.properties");
        users.add(file, properties, new TransientNio2BlobStore(), "user");
        properties.setProperty("s3proxy.credential", "rotated-secret");
        save(properties, "user.properties");
        assertThat(users.locateBlobStore("key", "b", "k").credential())
                .contains("rotated-secret");
        properties.setProperty("s3proxy.oci.enabled", "false");
        save(properties, "user.properties");
        assertThat(users.locateBlobStore("key", "b", "k")).isNull();
        properties.remove("s3proxy.oci.enabled");
        properties.setProperty("s3proxy.oci.user-id", "administrator");
        save(properties, "user.properties");
        assertThat(users.locateBlobStore("key", "b", "k")).isNull();
        Files.delete(file);
        assertThat(users.locateBlobStore("key", "b", "k")).isNull();
    }

    @Test
    public void malformedSecretAndBackendRemappingFailClosed() throws Exception {
        var users = new OciNativeUsers();
        var properties = configuration("key", "user");
        Path file = save(properties, "user.properties");
        users.add(file, properties, new TransientNio2BlobStore(), "user");
        properties.setProperty("jclouds.endpoint", "https://other.example");
        save(properties, "user.properties");
        assertThat(users.locateBlobStore("key", "b", "k")).isNull();
        Files.writeString(file, "broken=\\uXXXX");
        assertThat(users.locateBlobStore("key", "b", "k")).isNull();
        properties = configuration("key", "user");
        properties.remove("s3proxy.credential");
        save(properties, "user.properties");
        assertThat(users.locateBlobStore("key", "b", "k")).isNull();
    }

    @Test
    public void refusesWrongUserBindingAndDuplicateKey() throws Exception {
        var users = new OciNativeUsers();
        var properties = configuration("key", "user");
        Path file = save(properties, "user.properties");
        assertThatThrownBy(() -> users.add(file, properties,
                new TransientNio2BlobStore(), "administrator"))
                .isInstanceOf(IllegalArgumentException.class);
        users.add(file, properties, new TransientNio2BlobStore(), "user");
        assertThatThrownBy(() -> users.add(file, properties,
                new TransientNio2BlobStore(), "user"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void refusesUnsignedModeAndLocalBucketRouting() throws Exception {
        var users = new OciNativeUsers();
        var properties = configuration("key", "user");
        Path file = save(properties, "user.properties");
        properties.setProperty("s3proxy.authorization", "none");
        assertThatThrownBy(() -> users.add(file, properties,
                new TransientNio2BlobStore(), "user"))
                .isInstanceOf(IllegalArgumentException.class);
        properties.setProperty("s3proxy.authorization", "aws-v4");
        properties.setProperty("s3proxy.bucket-locator.1", "*");
        assertThatThrownBy(() -> users.add(file, properties,
                new TransientNio2BlobStore(), "user"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    public void refusesMixingNativeUsersWithOtherProviders() throws Exception {
        var users = new OciNativeUsers();
        var properties = configuration("key", "user");
        users.add(save(properties, "user.properties"), properties,
                new TransientNio2BlobStore(), "user");
        users.validateConfigurationCount(1);
        assertThatThrownBy(() -> users.validateConfigurationCount(2))
                .isInstanceOf(IllegalArgumentException.class);
    }

}

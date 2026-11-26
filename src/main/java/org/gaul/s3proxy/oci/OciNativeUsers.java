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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.gaul.s3proxy.AccessGrant;
import org.gaul.s3proxy.BlobStoreLocator;
import org.gaul.s3proxy.blobstore.BlobStore;
import org.jspecify.annotations.Nullable;

/** Native user bindings; never selects a shared or anonymous fallback. */
public final class OciNativeUsers implements BlobStoreLocator {
    private static final List<String> BINDING_PROPERTIES = List.of(
            "jclouds.provider", "jclouds.endpoint", "s3proxy.authorization",
            "s3proxy.identity", "s3proxy.oci.user-id", "s3proxy.oci.namespace",
            "s3proxy.oci.compartment", "s3proxy.oci.config-file",
            "s3proxy.oci.profile");

    private record Binding(Path file, Map<String, String> properties,
            BlobStore store) {
    }

    private final Map<String, Binding> users = new HashMap<>();

    /** Registration is complete before the server starts. */
    public void add(Path file, Properties properties, BlobStore store,
            String nativeUser) {
        if (!"aws-v4".equals(properties.getProperty(
                "s3proxy.authorization"))) {
            throw new IllegalArgumentException(
                    "Native user bindings require s3proxy.authorization=aws-v4");
        }
        String identity = required(properties, "s3proxy.identity");
        required(properties, "s3proxy.credential");
        if (!nativeUser.equals(required(properties, "s3proxy.oci.user-id"))) {
            throw new IllegalArgumentException(
                    "S3 key must bind to the native profile user");
        }
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith("s3proxy.bucket-locator.")) {
                throw new IllegalArgumentException(
                        "Native user bindings do not allow bucket locators");
            }
        }
        if (users.containsKey(identity)) {
            throw new IllegalArgumentException(
                    "Duplicate S3 access key in native user bindings");
        }
        var binding = new Binding(file.toAbsolutePath(), snapshot(properties),
                store);
        if (readGrant(binding) == null) {
            // A disabled entry is valid, but missing/mismatched identity
            // settings and unreadable files must not make startup appear valid.
            var current = load(file);
            if (!binding.properties().equals(snapshot(current))) {
                throw new IllegalArgumentException(
                        "Native identity settings must be in the properties file");
            }
        }
        users.put(identity, binding);
    }

    public void validateConfigurationCount(int configurations) {
        if (!users.isEmpty() && users.size() != configurations) {
            throw new IllegalArgumentException(
                    "Native user authorization cannot mix storage providers");
        }
    }

    public int size() {
        return users.size();
    }

    @Override
    public @Nullable AccessGrant locateBlobStore(@Nullable String identity,
            @Nullable String container, @Nullable String blob) {
        if (identity == null) {
            return null;
        }
        var binding = users.get(identity);
        return binding == null ? null : readGrant(binding);
    }

    @Override
    public boolean scopesBuckets() {
        // Native IAM handles each user's own bucket listing and operations.
        return false;
    }

    @Override
    public boolean allowsAnonymousRequests() {
        return false;
    }

    private static @Nullable AccessGrant readGrant(Binding binding) {
        try {
            var current = load(binding.file());
            if (!"true".equalsIgnoreCase(current.getProperty(
                    "s3proxy.oci.enabled", "true")) ||
                    !binding.properties().equals(snapshot(current))) {
                return null;
            }
            String secret = current.getProperty("s3proxy.credential");
            if (secret == null || secret.isBlank()) {
                return null;
            }
            return new AccessGrant(secret, binding.store());
        } catch (IllegalArgumentException error) {
            // No stale credential or different principal on read/parse failure.
            return null;
        }
    }

    private static Properties load(Path file) {
        var properties = new Properties();
        try (var stream = Files.newInputStream(file)) {
            properties.load(stream);
        } catch (IOException error) {
            throw new IllegalArgumentException(
                    "Cannot read native user properties file", error);
        }
        return properties;
    }

    private static Map<String, String> snapshot(Properties properties) {
        var result = new HashMap<String, String>();
        for (String name : BINDING_PROPERTIES) {
            String value = properties.getProperty(name, "");
            if (name.equals("jclouds.provider") && value.equals("pca-native")) {
                value = "oci-native";
            } else if (name.equals("s3proxy.oci.profile") && value.isEmpty()) {
                value = "DEFAULT";
            }
            result.put(name, value);
        }
        return Map.copyOf(result);
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " +
                    name);
        }
        return value;
    }
}

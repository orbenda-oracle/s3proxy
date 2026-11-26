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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;

import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.model.Range;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.objectstorage.model.CommitMultipartUploadDetails;
import com.oracle.bmc.objectstorage.model.CommitMultipartUploadPartDetails;
import com.oracle.bmc.objectstorage.model.CreateBucketDetails;
import com.oracle.bmc.objectstorage.model.CreateMultipartUploadDetails;
import com.oracle.bmc.retrier.RetryConfiguration;

import org.gaul.s3proxy.blobstore.BlobStore;
import org.gaul.s3proxy.blobstore.S3Exceptions;
import org.gaul.s3proxy.blobstore.SdkRequests;
import org.gaul.s3proxy.blobstore.domain.MultipartUpload;
import org.jspecify.annotations.Nullable;

import software.amazon.awssdk.awscore.AwsRequest;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.BucketCannedACL;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.CopyObjectResult;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListBucketsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/** S3 operations translated onto the OCI/PCA native Object Storage API. */
public final class OciNativeBlobStore implements BlobStore {
    private final ObjectStorage client;
    private final String namespace;
    private final String compartment;
    private final @Nullable String nativeUserId;

    public OciNativeBlobStore(ObjectStorage client, String namespace,
            String compartment) {
        this(client, namespace, compartment, null);
    }

    private OciNativeBlobStore(ObjectStorage client, String namespace,
            String compartment, @Nullable String nativeUserId) {
        this.nativeUserId = nativeUserId;
        this.client = client;
        this.namespace = namespace;
        this.compartment = compartment;
    }

    public static OciNativeBlobStore create(Properties properties) {
        String endpoint = required(properties, "jclouds.endpoint");
        URI uri = URI.create(endpoint);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null ||
                uri.getUserInfo() != null || uri.getQuery() != null ||
                uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "jclouds.endpoint must be an HTTPS native endpoint");
        }
        String namespace = required(properties, "s3proxy.oci.namespace");
        String compartment = required(properties, "s3proxy.oci.compartment");
        String config = required(properties, "s3proxy.oci.config-file");
        String user = required(properties, "s3proxy.oci.user-id");
        String profile = properties.getProperty("s3proxy.oci.profile",
                "DEFAULT");
        try {
            var auth = new ConfigFileAuthenticationDetailsProvider(config,
                    profile);
            if (!user.equals(auth.getUserId())) {
                throw new IllegalArgumentException(
                        "s3proxy.oci.user-id does not match OCI profile user");
            }
            // Never retry an already-consumed streaming request body.
            var client = ObjectStorageClient.builder().endpoint(endpoint)
                    .configuration(ClientConfiguration.builder()
                            .retryConfiguration(RetryConfiguration
                                    .NO_RETRY_CONFIGURATION).build())
                    .build(auth);
            return new OciNativeBlobStore(client, namespace, compartment, user);
        } catch (IOException ioe) {
            throw new UncheckedIOException("Cannot read OCI config", ioe);
        }
    }

    public String nativeUserId() {
        if (nativeUserId == null) {
            throw new IllegalStateException(
                    "Backend has no verified native user binding");
        }
        return nativeUserId;
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required property: " +
                    name);
        }
        return value;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception error) {
            throw new IllegalStateException("Cannot close native client", error);
        }
    }

    @Override
    public ListBucketsResponse list() {
        var buckets = new ArrayList<Bucket>();
        String page = null;
        do {
            String current = page;
            var response = call(() -> client.listBuckets(
                    com.oracle.bmc.objectstorage.requests.ListBucketsRequest
                            .builder().namespaceName(namespace)
                            .compartmentId(compartment).page(current).build()),
                    "", null);
            for (var bucket : response.getItems()) {
                buckets.add(Bucket.builder().name(bucket.getName())
                        .creationDate(instant(bucket.getTimeCreated())).build());
            }
            page = response.getOpcNextPage();
        } while (page != null);
        return ListBucketsResponse.builder().buckets(buckets).build();
    }

    @Override
    public ListObjectsV2Response list(ListObjectsV2Request request) {
        privateRead(request);
        int limit = request.maxKeys() == null ? 1000 : request.maxKeys();
        if (limit == 0) {
            headBucket(HeadBucketRequest.builder().bucket(request.bucket())
                    .build());
            return ListObjectsV2Response.builder().isTruncated(false)
                    .keyCount(0).build();
        }
        var result = call(() -> client.listObjects(
                com.oracle.bmc.objectstorage.requests.ListObjectsRequest
                        .builder().namespaceName(namespace)
                        .bucketName(request.bucket()).prefix(request.prefix())
                        .delimiter(request.delimiter()).limit(limit)
                        .start(request.continuationToken())
                        .startAfter(request.continuationToken() == null ?
                                request.startAfter() : null)
                        .fields("name,size,etag,timeModified").build()),
                request.bucket(), null).getListObjects();
        var objects = result.getObjects().stream().map(object ->
                S3Object.builder().key(object.getName()).size(object.getSize())
                        .eTag(object.getEtag())
                        .lastModified(instant(object.getTimeModified()))
                        .storageClass("STANDARD").build()).toList();
        var prefixes = result.getPrefixes() == null ? List.<CommonPrefix>of() :
                result.getPrefixes().stream().map(prefix ->
                        CommonPrefix.builder().prefix(prefix).build()).toList();
        return ListObjectsV2Response.builder().contents(objects)
                .commonPrefixes(prefixes)
                .keyCount(objects.size() + prefixes.size())
                .nextContinuationToken(result.getNextStartWith())
                .isTruncated(result.getNextStartWith() != null).build();
    }

    @Override
    public HeadBucketResponse headBucket(HeadBucketRequest request) {
        privateRead(request);
        call(() -> client.headBucket(com.oracle.bmc.objectstorage.requests
                .HeadBucketRequest.builder().namespaceName(namespace)
                .bucketName(request.bucket()).build()), request.bucket(), null);
        return HeadBucketResponse.builder().build();
    }

    @Override
    public CreateBucketResponse createContainer(CreateBucketRequest request) {
        if ((request.acl() != null &&
                request.acl() != BucketCannedACL.PRIVATE) ||
                Boolean.TRUE.equals(request.objectLockEnabledForBucket())) {
            throw unsupported("Public ACLs and object lock");
        }
        call(() -> client.createBucket(com.oracle.bmc.objectstorage.requests
                .CreateBucketRequest.builder().namespaceName(namespace)
                .createBucketDetails(CreateBucketDetails.builder()
                        .name(request.bucket()).compartmentId(compartment)
                        .publicAccessType(CreateBucketDetails.PublicAccessType
                                .NoPublicAccess).build()).build()),
                request.bucket(), null);
        return CreateBucketResponse.builder().build();
    }

    @Override
    public void deleteBucket(String container) {
        call(() -> client.deleteBucket(com.oracle.bmc.objectstorage.requests
                .DeleteBucketRequest.builder().namespaceName(namespace)
                .bucketName(container).build()), container, null);
    }

    @Override
    public BucketCannedACL getContainerAccess(String container) {
        requirePrivateBucket(container);
        return BucketCannedACL.PRIVATE;
    }

    @Override
    public void setContainerAccess(String container, BucketCannedACL access) {
        // Even PRIVATE cannot be promised for an existing public native
        // bucket without changing its actual native permissions.
        throw unsupported("ACL changes");
    }

    @Override
    public ObjectCannedACL getBlobAccess(String container, String name) {
        blobMetadata(HeadObjectRequest.builder().bucket(container).key(name)
                .build());
        requirePrivateBucket(container);
        return ObjectCannedACL.PRIVATE;
    }

    private void requirePrivateBucket(String bucket) {
        var result = call(() -> client.getBucket(
                com.oracle.bmc.objectstorage.requests.GetBucketRequest.builder()
                        .namespaceName(namespace).bucketName(bucket).build()),
                bucket, null).getBucket();
        if (result.getPublicAccessType() != com.oracle.bmc.objectstorage.model
                .Bucket.PublicAccessType.NoPublicAccess) {
            throw unsupported("Private ACLs on public native buckets");
        }
    }

    private static void extraEncryption(@Nullable String context,
            @Nullable Boolean bucketKey, @Nullable String customerKey,
            @Nullable String customerKeyMD5) {
        if (context != null || bucketKey != null || customerKey != null ||
                customerKeyMD5 != null) {
            throw unsupported("Encryption context, bucket keys and SSE-C");
        }
    }

    @Override
    public void setBlobAccess(String container, String name,
            ObjectCannedACL access) {
        throw unsupported("ACL changes");
    }

    @Override
    public PutObjectResponse putBlob(PutObjectRequest request,
            InputStream payload) {
        try (payload) {
            writeOptions(request.acl(), request.serverSideEncryptionAsString(),
                    request.sseCustomerAlgorithm(), request.ssekmsKeyId(),
                    request.tagging(), request.storageClass());
            extraEncryption(request.ssekmsEncryptionContext(),
                    request.bucketKeyEnabled(), request.sseCustomerKey(),
                    request.sseCustomerKeyMD5());
            requirePrivateBucket(request.bucket());
            if (request.expires() != null ||
                    request.websiteRedirectLocation() != null ||
                    request.objectLockMode() != null ||
                    request.objectLockLegalHoldStatus() != null ||
                    request.objectLockRetainUntilDate() != null) {
                throw unsupported("Expires, redirects and object lock");
            }
            var response = call(() -> client.putObject(
                    com.oracle.bmc.objectstorage.requests.PutObjectRequest
                            .builder().namespaceName(namespace)
                            .bucketName(request.bucket())
                            .objectName(request.key())
                            .contentLength(request.contentLength())
                            .putObjectBody(payload)
                            .contentMD5(request.contentMD5())
                            .contentType(request.contentType())
                            .contentEncoding(request.contentEncoding())
                            .contentDisposition(request.contentDisposition())
                            .contentLanguage(request.contentLanguage())
                            .cacheControl(request.cacheControl())
                            .opcMeta(request.metadata())
                            .ifMatch(request.ifMatch())
                            .ifNoneMatch(request.ifNoneMatch())
                            .retryConfiguration(RetryConfiguration
                                    .NO_RETRY_CONFIGURATION).build()),
                    request.bucket(), request.key());
            return PutObjectResponse.builder().eTag(response.getETag()).build();
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    @Override
    public HeadObjectResponse blobMetadata(HeadObjectRequest request) {
        privateRead(request);
        readOptions(request.versionId(), request.sseCustomerAlgorithm(),
                request.ifModifiedSince(), request.ifUnmodifiedSince());
        extraEncryption(null, null, request.sseCustomerKey(),
                request.sseCustomerKeyMD5());
        var response = call(() -> client.headObject(
                com.oracle.bmc.objectstorage.requests.HeadObjectRequest
                        .builder().namespaceName(namespace)
                        .bucketName(request.bucket()).objectName(request.key())
                        .ifMatch(request.ifMatch())
                        .ifNoneMatch(request.ifNoneMatch()).build()),
                request.bucket(), request.key());
        if (response.isNotModified()) {
            throw S3Exceptions.fromStatusCode(304, response.getETag(),
                    Map.of(), null);
        }
        return HeadObjectResponse.builder().eTag(response.getETag())
                .contentLength(response.getContentLength())
                .contentType(response.getContentType())
                .contentEncoding(response.getContentEncoding())
                .contentDisposition(response.getContentDisposition())
                .contentLanguage(response.getContentLanguage())
                .cacheControl(response.getCacheControl())
                .lastModified(instant(response.getLastModified()))
                .metadata(metadata(response.getOpcMeta())).build();
    }

    @Override
    public ResponseInputStream<GetObjectResponse> getBlob(
            GetObjectRequest request) {
        privateRead(request);
        readOptions(request.versionId(), request.sseCustomerAlgorithm(),
                request.ifModifiedSince(), request.ifUnmodifiedSince());
        extraEncryption(null, null, request.sseCustomerKey(),
                request.sseCustomerKeyMD5());
        var range = SdkRequests.parseRange(request.range());
        var nativeRange = range == null ? null :
                new Range(range.first(), range.last());
        var response = call(() -> client.getObject(
                com.oracle.bmc.objectstorage.requests.GetObjectRequest
                        .builder().namespaceName(namespace)
                        .bucketName(request.bucket()).objectName(request.key())
                        .ifMatch(request.ifMatch())
                        .ifNoneMatch(request.ifNoneMatch()).range(nativeRange)
                        .build()), request.bucket(), request.key());
        if (response.isNotModified()) {
            throw S3Exceptions.fromStatusCode(304, response.getETag(),
                    Map.of(), null);
        }
        var result = GetObjectResponse.builder().eTag(response.getETag())
                .contentLength(response.getContentLength())
                .contentType(response.getContentType())
                .contentEncoding(response.getContentEncoding())
                .contentDisposition(response.getContentDisposition())
                .contentLanguage(response.getContentLanguage())
                .cacheControl(response.getCacheControl())
                .lastModified(instant(response.getLastModified()))
                .metadata(metadata(response.getOpcMeta()));
        if (response.getContentRange() != null) {
            result.contentRange("bytes " + response.getContentRange()
                    .getStartByte() + "-" + response.getContentRange()
                    .getEndByte() + "/" + (response.getContentRange()
                    .getContentLength() == null ? "*" :
                    response.getContentRange().getContentLength()));
        }
        return new ResponseInputStream<>(result.build(),
                response.getInputStream());
    }

    @Override
    public DeleteObjectResponse removeBlob(DeleteObjectRequest request) {
        if (request.versionId() != null || request.ifMatchLastModifiedTime() !=
                null || request.ifMatchSize() != null ||
                Boolean.TRUE.equals(request.bypassGovernanceRetention())) {
            throw unsupported("Versioned/conditional delete or retention");
        }
        try {
            client.deleteObject(com.oracle.bmc.objectstorage.requests
                    .DeleteObjectRequest.builder().namespaceName(namespace)
                    .bucketName(request.bucket()).objectName(request.key())
                    .ifMatch(request.ifMatch()).build());
        } catch (BmcException error) {
            if (error.getStatusCode() != 404 ||
                    !"ObjectNotFound".equals(error.getServiceCode())) {
                throw translate(error, request.bucket(), request.key());
            }
        }
        return DeleteObjectResponse.builder().build();
    }

    @Override
    public CopyObjectResponse copyBlob(CopyObjectRequest request) {
        writeOptions(request.acl(), request.serverSideEncryptionAsString(),
                request.sseCustomerAlgorithm(), request.ssekmsKeyId(),
                request.tagging(), request.storageClass());
        extraEncryption(request.ssekmsEncryptionContext(),
                request.bucketKeyEnabled(), request.sseCustomerKey(),
                request.sseCustomerKeyMD5());
        if (request.copySourceSSECustomerAlgorithm() != null ||
                request.copySourceSSECustomerKey() != null ||
                request.copySourceSSECustomerKeyMD5() != null ||
                request.expires() != null ||
                request.websiteRedirectLocation() != null ||
                request.objectLockMode() != null ||
                request.objectLockLegalHoldStatus() != null ||
                request.objectLockRetainUntilDate() != null) {
            throw unsupported("Copy encryption, expiry, redirects or lock");
        }
        try (var source = getBlob(GetObjectRequest.builder()
                .bucket(request.sourceBucket()).key(request.sourceKey())
                .versionId(request.sourceVersionId())
                .ifMatch(request.copySourceIfMatch())
                .ifNoneMatch(request.copySourceIfNoneMatch())
                .ifModifiedSince(request.copySourceIfModifiedSince())
                .ifUnmodifiedSince(request.copySourceIfUnmodifiedSince())
                .build())) {
            var original = source.response();
            boolean replace = request.metadataDirective() ==
                    MetadataDirective.REPLACE;
            var result = putBlob(PutObjectRequest.builder()
                    .bucket(request.destinationBucket())
                    .key(request.destinationKey())
                    .contentLength(original.contentLength())
                    .ifMatch(request.ifMatch())
                    .ifNoneMatch(request.ifNoneMatch())
                    .metadata(replace ? request.metadata() : original.metadata())
                    .contentType(replace ? request.contentType() :
                            original.contentType())
                    .contentEncoding(replace ? request.contentEncoding() :
                            original.contentEncoding())
                    .contentDisposition(replace ? request.contentDisposition() :
                            original.contentDisposition())
                    .contentLanguage(replace ? request.contentLanguage() :
                            original.contentLanguage())
                    .cacheControl(replace ? request.cacheControl() :
                            original.cacheControl()).build(), source);
            return CopyObjectResponse.builder().copyObjectResult(
                    CopyObjectResult.builder().eTag(result.eTag()).build())
                    .build();
        } catch (S3Exception error) {
            if (error.statusCode() == 304) {
                throw S3Exceptions.preconditionFailed(null);
            }
            throw error;
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    @Override
    public MultipartUpload initiateMultipartUpload(
            CreateMultipartUploadRequest request) {
        writeOptions(request.acl(), request.serverSideEncryptionAsString(),
                request.sseCustomerAlgorithm(), request.ssekmsKeyId(),
                request.tagging(), request.storageClass());
        extraEncryption(request.ssekmsEncryptionContext(),
                request.bucketKeyEnabled(), request.sseCustomerKey(),
                request.sseCustomerKeyMD5());
        requirePrivateBucket(request.bucket());
        if (request.expires() != null ||
                request.websiteRedirectLocation() != null ||
                request.objectLockMode() != null ||
                request.objectLockLegalHoldStatus() != null ||
                request.objectLockRetainUntilDate() != null) {
            throw unsupported("Multipart expiry, redirects and object lock");
        }
        var response = call(() -> client.createMultipartUpload(
                com.oracle.bmc.objectstorage.requests
                        .CreateMultipartUploadRequest.builder()
                        .namespaceName(namespace).bucketName(request.bucket())
                        .createMultipartUploadDetails(
                                CreateMultipartUploadDetails.builder()
                                        .object(request.key())
                                        .metadata(request.metadata())
                                        .contentType(request.contentType())
                                        .contentEncoding(
                                                request.contentEncoding())
                                        .contentDisposition(
                                                request.contentDisposition())
                                        .contentLanguage(
                                                request.contentLanguage())
                                        .cacheControl(request.cacheControl())
                                        .build()).build()),
                request.bucket(), request.key());
        return new MultipartUpload(response.getMultipartUpload().getUploadId(),
                request);
    }

    @Override
    public UploadPartResponse uploadMultipartPart(MultipartUpload mpu,
            UploadPartRequest request, InputStream payload) {
        try (payload) {
            extraEncryption(null, null, request.sseCustomerKey(),
                    request.sseCustomerKeyMD5());
            if (request.sseCustomerAlgorithm() != null) {
                throw unsupported("SSE-C");
            }
            var response = call(() -> client.uploadPart(
                    com.oracle.bmc.objectstorage.requests.UploadPartRequest
                            .builder().namespaceName(namespace)
                            .bucketName(mpu.containerName())
                            .objectName(mpu.blobName()).uploadId(mpu.id())
                            .uploadPartNum(request.partNumber())
                            .contentLength(request.contentLength())
                            .contentMD5(request.contentMD5())
                            .uploadPartBody(payload)
                            .retryConfiguration(RetryConfiguration
                                    .NO_RETRY_CONFIGURATION).build()),
                    mpu.containerName(), mpu.blobName());
            return UploadPartResponse.builder().eTag(response.getETag()).build();
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    @Override
    public CompleteMultipartUploadResponse completeMultipartUpload(
            MultipartUpload mpu, CompleteMultipartUploadRequest request) {
        extraEncryption(null, null, request.sseCustomerKey(),
                request.sseCustomerKeyMD5());
        requirePrivateBucket(mpu.containerName());
        var parts = request.multipartUpload().parts().stream()
                .map(part -> CommitMultipartUploadPartDetails.builder()
                        .partNum(part.partNumber()).etag(part.eTag()).build())
                .toList();
        var response = call(() -> client.commitMultipartUpload(
                com.oracle.bmc.objectstorage.requests
                        .CommitMultipartUploadRequest.builder()
                        .namespaceName(namespace)
                        .bucketName(mpu.containerName())
                        .objectName(mpu.blobName()).uploadId(mpu.id())
                        .ifMatch(request.ifMatch())
                        .ifNoneMatch(request.ifNoneMatch())
                        .commitMultipartUploadDetails(
                                CommitMultipartUploadDetails.builder()
                                        .partsToCommit(parts).build()).build()),
                mpu.containerName(), mpu.blobName());
        return CompleteMultipartUploadResponse.builder()
                .eTag(response.getETag()).bucket(mpu.containerName())
                .key(mpu.blobName()).build();
    }

    @Override
    public void abortMultipartUpload(MultipartUpload mpu) {
        call(() -> client.abortMultipartUpload(
                com.oracle.bmc.objectstorage.requests.AbortMultipartUploadRequest
                        .builder().namespaceName(namespace)
                        .bucketName(mpu.containerName())
                        .objectName(mpu.blobName()).uploadId(mpu.id()).build()),
                mpu.containerName(), mpu.blobName());
    }

    @Override
    public List<Part> listMultipartUpload(MultipartUpload mpu) {
        var parts = new ArrayList<Part>();
        String page = null;
        do {
            String current = page;
            var response = call(() -> client.listMultipartUploadParts(
                    com.oracle.bmc.objectstorage.requests
                            .ListMultipartUploadPartsRequest.builder()
                            .namespaceName(namespace)
                            .bucketName(mpu.containerName())
                            .objectName(mpu.blobName()).uploadId(mpu.id())
                            .page(current).build()), mpu.containerName(),
                    mpu.blobName());
            for (var part : response.getItems()) {
                parts.add(Part.builder().partNumber(part.getPartNumber())
                        .size(part.getSize()).eTag(part.getEtag()).build());
            }
            page = response.getOpcNextPage();
        } while (page != null);
        parts.sort(Comparator.comparing(Part::partNumber));
        return parts;
    }

    @Override
    public List<software.amazon.awssdk.services.s3.model.MultipartUpload>
            listMultipartUploads(String container) {
        var uploads = new ArrayList<software.amazon.awssdk.services.s3.model
                .MultipartUpload>();
        String page = null;
        do {
            String current = page;
            var response = call(() -> client.listMultipartUploads(
                    com.oracle.bmc.objectstorage.requests
                            .ListMultipartUploadsRequest.builder()
                            .namespaceName(namespace).bucketName(container)
                            .page(current).build()), container, null);
            for (var upload : response.getItems()) {
                uploads.add(software.amazon.awssdk.services.s3.model
                        .MultipartUpload.builder().key(upload.getObject())
                        .uploadId(upload.getUploadId())
                        .initiated(instant(upload.getTimeCreated())).build());
            }
            page = response.getOpcNextPage();
        } while (page != null);
        return uploads;
    }

    @Override
    public long getMinimumMultipartPartSize() {
        return 5L * 1024 * 1024;
    }

    private static void privateRead(AwsRequest request) {
        if (SdkRequests.onlyIfPublicRead(request)) {
            throw S3Exceptions.accessDenied("Native backend is private", null);
        }
    }

    private static void readOptions(@Nullable String version,
            @Nullable String sse, @Nullable Instant modified,
            @Nullable Instant unmodified) {
        if (version != null || sse != null || modified != null ||
                unmodified != null) {
            throw unsupported("Version IDs, SSE-C and date conditions");
        }
    }

    private static void writeOptions(@Nullable ObjectCannedACL acl,
            @Nullable String encryption, @Nullable String customerEncryption,
            @Nullable String kmsKey, @Nullable String tagging,
            @Nullable StorageClass storageClass) {
        if ((acl != null && acl != ObjectCannedACL.PRIVATE) ||
                encryption != null || customerEncryption != null ||
                kmsKey != null || tagging != null ||
                (storageClass != null && storageClass !=
                        StorageClass.STANDARD)) {
            throw unsupported("Public ACLs, requested encryption, tags and " +
                    "non-standard storage classes");
        }
    }

    private static S3Exception unsupported(String feature) {
        return S3Exceptions.notImplemented(feature +
                " are not supported by oci-native");
    }

    private static Map<String, String> metadata(
            @Nullable Map<String, String> values) {
        return values == null ? Map.of() : values;
    }

    @Nullable
    private static Instant instant(@Nullable Date date) {
        return date == null ? null : date.toInstant();
    }

    private static <T> T call(Supplier<T> operation, String bucket,
            @Nullable String key) {
        try {
            return operation.get();
        } catch (BmcException error) {
            throw translate(error, bucket, key);
        }
    }

    private static S3Exception translate(BmcException error, String bucket,
            @Nullable String key) {
        return switch (error.getStatusCode()) {
        case 401, 403 -> S3Exceptions.accessDenied(
                "Native Object Storage refused access", null);
        case 404 -> {
            if ("MultipartUploadNotFound".equals(error.getServiceCode())) {
                yield S3Exceptions.withCode(404, "NoSuchUpload",
                        "The specified multipart upload does not exist", null);
            }
            yield key == null ? S3Exceptions.noSuchBucket(bucket, null) :
                    S3Exceptions.noSuchKey(bucket, key, null);
        }
        case 409 -> S3Exceptions.withCode(409,
                "BucketNotEmpty".equals(error.getServiceCode()) ?
                        "BucketNotEmpty" : "OperationAborted",
                "Native Object Storage conflict", null);
        case 412 -> S3Exceptions.preconditionFailed(null);
        case 429 -> S3Exceptions.slowDown(null);
        case 502, 503, 504 -> S3Exceptions.serviceUnavailable(null);
        default -> S3Exceptions.fromStatusCode(
                error.getStatusCode() < 0 ? 502 : error.getStatusCode());
        };
    }
}

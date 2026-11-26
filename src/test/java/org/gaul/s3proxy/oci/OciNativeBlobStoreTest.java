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
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.model.CommitMultipartUploadDetails;
import com.oracle.bmc.objectstorage.model.ListObjects;
import com.oracle.bmc.objectstorage.model.ObjectSummary;
import com.oracle.bmc.objectstorage.responses.GetObjectResponse;
import com.oracle.bmc.objectstorage.responses.ListObjectsResponse;
import com.oracle.bmc.objectstorage.responses.PutObjectResponse;

import org.gaul.s3proxy.blobstore.S3Exceptions;
import org.gaul.s3proxy.blobstore.domain.MultipartUpload;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

public final class OciNativeBlobStoreTest {
    private static ObjectStorage client(BiFunction<String, Object, Object> call) {
        return rawClient((method, arg) -> {
            if ("getBucket".equals(method)) {
                return com.oracle.bmc.objectstorage.responses.GetBucketResponse
                        .builder().bucket(com.oracle.bmc.objectstorage.model
                                .Bucket.builder().publicAccessType(com.oracle
                                        .bmc.objectstorage.model.Bucket
                                                .PublicAccessType.NoPublicAccess)
                                .build()).build();
            }
            return call.apply(method, arg);
        });
    }

    private static ObjectStorage rawClient(
            BiFunction<String, Object, Object> call) {
        return (ObjectStorage) Proxy.newProxyInstance(
                ObjectStorage.class.getClassLoader(),
                new Class<?>[] {ObjectStorage.class},
                (proxy, method, args) -> call.apply(method.getName(),
                        args == null ? null : args[0]));
    }

    @Test
    public void streamsPutAndPreservesNativeMetadataAndConditions() {
        var seen = new AtomicReference<com.oracle.bmc.objectstorage.requests
                .PutObjectRequest>();
        var closed = new AtomicBoolean();
        var payload = new ByteArrayInputStream(new byte[] {1, 2, 3}) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        var store = new OciNativeBlobStore(client((method, arg) -> {
            var request = (com.oracle.bmc.objectstorage.requests
                    .PutObjectRequest) arg;
            seen.set(request);
            assertThat(request.getPutObjectBody()).isSameAs(payload);
            return PutObjectResponse.builder().eTag("native-etag").build();
        }), "native-ns", "compartment");
        var response = store.putBlob(PutObjectRequest.builder()
                .bucket("bucket").key("a/+ שלום").contentLength(3L)
                .contentType("text/plain").contentMD5("md5-base64")
                .ifNoneMatch("*").metadata(Map.of("purpose", "test"))
                .build(), payload);
        assertThat(response.eTag()).isEqualTo("native-etag");
        assertThat(seen.get().getNamespaceName()).isEqualTo("native-ns");
        assertThat(seen.get().getObjectName()).isEqualTo("a/+ שלום");
        assertThat(seen.get().getOpcMeta()).containsEntry("purpose", "test");
        assertThat(seen.get().getContentMD5()).isEqualTo("md5-base64");
        assertThat(seen.get().getIfNoneMatch()).isEqualTo("*");
        assertThat(closed).isTrue();
    }

    @Test
    public void getStreamsRangeAndMetadataWithoutBuffering() throws Exception {
        var bytes = new ByteArrayInputStream("abc".getBytes(
                StandardCharsets.UTF_8));
        var store = new OciNativeBlobStore(client((method, arg) -> {
            var request = (com.oracle.bmc.objectstorage.requests
                    .GetObjectRequest) arg;
            assertThat(request.getRange().getStartByte()).isEqualTo(2L);
            assertThat(request.getRange().getEndByte()).isEqualTo(4L);
            assertThat(request.getIfMatch()).isEqualTo("etag");
            return GetObjectResponse.builder().inputStream(bytes)
                    .contentLength(3L).eTag("etag")
                    .lastModified(new Date(1000))
                    .opcMeta(Map.of("hello", "world"))
                    .contentRange(com.oracle.bmc.model.Range.parse("bytes 2-4/9"))
                    .build();
        }), "ns", "compartment");
        try (var response = store.getBlob(GetObjectRequest.builder()
                .bucket("bucket").key("key").range("bytes=2-4")
                .ifMatch("etag").build())) {
            assertThat(bytes.available()).isEqualTo(3);
            assertThat(response.response().contentRange())
                    .isEqualTo("bytes 2-4/9");
            assertThat(response.response().metadata())
                    .containsEntry("hello", "world");
            assertThat(response.readAllBytes()).isEqualTo(
                    "abc".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void translatesInclusiveContinuationTokenAndExclusiveStartAfter() {
        var seen = new AtomicReference<com.oracle.bmc.objectstorage.requests
                .ListObjectsRequest>();
        var store = new OciNativeBlobStore(client((method, arg) -> {
            seen.set((com.oracle.bmc.objectstorage.requests
                    .ListObjectsRequest) arg);
            return ListObjectsResponse.builder().listObjects(ListObjects
                    .builder().objects(List.of(ObjectSummary.builder()
                            .name("prefix/key").size(5L).etag("etag")
                            .timeModified(new Date(1000)).build()))
                    .prefixes(List.of("prefix/sub/"))
                    .nextStartWith("next-key").build()).build();
        }), "ns", "compartment");
        var page = store.list(ListObjectsV2Request.builder().bucket("bucket")
                .prefix("prefix/").delimiter("/").maxKeys(2)
                .continuationToken("resume").startAfter("ignored").build());
        assertThat(seen.get().getStart()).isEqualTo("resume");
        assertThat(seen.get().getStartAfter()).isNull();
        assertThat(page.isTruncated()).isTrue();
        assertThat(page.nextContinuationToken()).isEqualTo("next-key");
        assertThat(page.contents().get(0).size()).isEqualTo(5L);
        assertThat(page.commonPrefixes().get(0).prefix())
                .isEqualTo("prefix/sub/");
        store.list(ListObjectsV2Request.builder().bucket("bucket")
                .startAfter("exclusive-key").build());
        assertThat(seen.get().getStart()).isNull();
        assertThat(seen.get().getStartAfter()).isEqualTo("exclusive-key");
    }

    @Test
    public void zeroMaxKeysDoesNotRequestAnUnboundedNativePage() {
        var store = new OciNativeBlobStore(client((method, arg) -> {
            assertThat(method).isEqualTo("headBucket");
            return com.oracle.bmc.objectstorage.responses.HeadBucketResponse
                    .builder().build();
        }), "ns", "compartment");
        var page = store.list(ListObjectsV2Request.builder()
                .bucket("bucket").maxKeys(0).build());
        assertThat(page.contents()).isEmpty();
        assertThat(page.isTruncated()).isFalse();
    }

    @Test
    public void native404IsNoSuchKeyButForbiddenIsNeverMissing() {
        var status = new java.util.concurrent.atomic.AtomicInteger(404);
        var store = new OciNativeBlobStore(client((method, arg) -> {
            throw new BmcException(status.get(), "ObjectNotFound", "detail",
                    "request-id", (Throwable) null);
        }), "ns", "compartment");
        assertThatThrownBy(() -> store.blobMetadata(HeadObjectRequest.builder()
                .bucket("bucket").key("key").build()))
                .isInstanceOf(software.amazon.awssdk.services.s3.model
                        .NoSuchKeyException.class);
        status.set(403);
        assertThatThrownBy(() -> store.blobMetadataIfPresent("bucket", "key"))
                .satisfies(error -> assertThat(S3Exceptions.errorCode(
                        (software.amazon.awssdk.services.s3.model
                                .S3Exception) error)).isEqualTo("AccessDenied"));
    }

    @Test
    public void refusesUnsupportedOptionsBeforeWritingAndClosesPayload() {
        var called = new AtomicBoolean();
        var store = new OciNativeBlobStore(client((method, arg) -> {
            called.set(true);
            throw new AssertionError("must not reach native API");
        }), "ns", "compartment");
        for (var request : List.of(
                PutObjectRequest.builder().bucket("b").key("k")
                        .contentLength(0L).ssekmsEncryptionContext("context")
                        .build(),
                PutObjectRequest.builder().bucket("b").key("k")
                        .contentLength(0L).bucketKeyEnabled(true).build(),
                PutObjectRequest.builder().bucket("b").key("k")
                        .contentLength(0L).acl(ObjectCannedACL.PUBLIC_READ)
                        .build(),
                PutObjectRequest.builder().bucket("b").key("k")
                        .contentLength(0L)
                        .serverSideEncryption(ServerSideEncryption.AWS_KMS)
                        .build())) {
            var closed = new AtomicBoolean();
            var payload = new ByteArrayInputStream(new byte[0]) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            };
            assertThatThrownBy(() -> store.putBlob(request, payload))
                    .satisfies(error -> assertThat(S3Exceptions.errorCode(
                            (software.amazon.awssdk.services.s3.model
                                    .S3Exception) error))
                            .isEqualTo("NotImplemented"));
            assertThat(closed).isTrue();
        }
        assertThat(called).isFalse();
    }

    @Test
    public void completesReconstructedMultipartUploadWithoutLocalState() {
        var seen = new AtomicReference<CommitMultipartUploadDetails>();
        var store = new OciNativeBlobStore(client((method, arg) -> {
            assertThat(method).isEqualTo("commitMultipartUpload");
            var request = (com.oracle.bmc.objectstorage.requests
                    .CommitMultipartUploadRequest) arg;
            assertThat(request.getUploadId()).isEqualTo("native-upload-id");
            assertThat(request.getIfNoneMatch()).isEqualTo("*");
            seen.set(request.getCommitMultipartUploadDetails());
            return com.oracle.bmc.objectstorage.responses
                    .CommitMultipartUploadResponse.builder()
                    .eTag("multipart-etag").build();
        }), "ns", "compartment");
        var mpu = new MultipartUpload("native-upload-id",
                CreateMultipartUploadRequest.builder()
                        .bucket("bucket").key("key").build());
        var response = store.completeMultipartUpload(mpu,
                CompleteMultipartUploadRequest.builder().ifNoneMatch("*")
                        .multipartUpload(CompletedMultipartUpload.builder()
                                .parts(CompletedPart.builder().partNumber(1)
                                        .eTag("part-etag").build()).build())
                        .build());
        assertThat(response.eTag()).isEqualTo("multipart-etag");
        assertThat(seen.get().getPartsToCommit().get(0).getPartNum())
                .isEqualTo(1);
        assertThat(seen.get().getPartsToCommit().get(0).getEtag())
                .isEqualTo("part-etag");
    }
    @Test
    public void privateObjectsCannotBeReadAnonymously() {
        var store = new OciNativeBlobStore(client((method, arg) -> {
            throw new AssertionError("Anonymous request reached native API");
        }), "ns", "compartment");
        assertThatThrownBy(() -> store.getBlob(GetObjectRequest.builder()
                .bucket("bucket").key("key").overrideConfiguration(
                        org.gaul.s3proxy.blobstore.SdkRequests
                                .ONLY_IF_PUBLIC_READ).build()))
                .satisfies(error -> assertThat(S3Exceptions.errorCode(
                        (software.amazon.awssdk.services.s3.model.S3Exception)
                                error)).isEqualTo("AccessDenied"));
    }

    @Test
    public void multipartPartsAreReadAcrossPagesAndSortedNumerically() {
        var pages = new java.util.ArrayList<String>();
        var store = new OciNativeBlobStore(client((method, arg) -> {
            var request = (com.oracle.bmc.objectstorage.requests
                    .ListMultipartUploadPartsRequest) arg;
            pages.add(request.getPage());
            boolean first = request.getPage() == null;
            return com.oracle.bmc.objectstorage.responses
                    .ListMultipartUploadPartsResponse.builder()
                    .items(List.of(com.oracle.bmc.objectstorage.model
                            .MultipartUploadPartSummary.builder()
                            .partNumber(first ? 2 : 1).etag("etag").size(5L)
                            .build()))
                    .opcNextPage(first ? "page-two" : null).build();
        }), "ns", "compartment");
        var parts = store.listMultipartUpload(new MultipartUpload("upload-id",
                CreateMultipartUploadRequest.builder().bucket("bucket")
                        .key("key").build()));
        assertThat(pages).containsExactly(null, "page-two");
        assertThat(parts).extracting(part -> part.partNumber())
                .containsExactly(1, 2);
    }

    @Test
    public void multipartUsesNativeIdAndStreamsPartsAndAborts() {
        var operations = new java.util.ArrayList<String>();
        var store = new OciNativeBlobStore(client((method, arg) -> {
            operations.add(method);
            return switch (method) {
            case "createMultipartUpload" -> {
                var request = (com.oracle.bmc.objectstorage.requests
                        .CreateMultipartUploadRequest) arg;
                assertThat(request.getCreateMultipartUploadDetails()
                        .getMetadata()).containsEntry("purpose", "test");
                yield com.oracle.bmc.objectstorage.responses
                        .CreateMultipartUploadResponse.builder()
                        .multipartUpload(com.oracle.bmc.objectstorage.model
                                .MultipartUpload.builder().uploadId("native-id")
                                .build()).build();
            }
            case "uploadPart" -> {
                var request = (com.oracle.bmc.objectstorage.requests
                        .UploadPartRequest) arg;
                assertThat(request.getUploadId()).isEqualTo("native-id");
                assertThat(request.getUploadPartNum()).isEqualTo(7);
                assertThat(request.getContentLength()).isEqualTo(3L);
                yield com.oracle.bmc.objectstorage.responses.UploadPartResponse
                        .builder().eTag("part-etag").build();
            }
            case "abortMultipartUpload" -> {
                var request = (com.oracle.bmc.objectstorage.requests
                        .AbortMultipartUploadRequest) arg;
                assertThat(request.getUploadId()).isEqualTo("native-id");
                yield com.oracle.bmc.objectstorage.responses
                        .AbortMultipartUploadResponse.builder().build();
            }
            default -> throw new AssertionError(method);
            };
        }), "ns", "compartment");
        var upload = store.initiateMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket("bucket")
                        .key("key").metadata(Map.of("purpose", "test")).build());
        assertThat(upload.id()).isEqualTo("native-id");
        var part = store.uploadMultipartPart(upload,
                software.amazon.awssdk.services.s3.model.UploadPartRequest
                        .builder().partNumber(7).contentLength(3L).build(),
                new ByteArrayInputStream(new byte[] {1, 2, 3}));
        assertThat(part.eTag()).isEqualTo("part-etag");
        store.abortMultipartUpload(upload);
        assertThat(operations).containsExactly("createMultipartUpload",
                "uploadPart", "abortMultipartUpload");
    }

    @Test
    public void copyPreservesDestinationConditionInNativePut() {
        var store = new OciNativeBlobStore(client((method, arg) -> {
            if ("getObject".equals(method)) {
                return GetObjectResponse.builder().contentLength(0L)
                        .inputStream(new ByteArrayInputStream(new byte[0]))
                        .build();
            }
            var request = (com.oracle.bmc.objectstorage.requests
                    .PutObjectRequest) arg;
            assertThat(request.getIfMatch()).isEqualTo("destination-etag");
            return PutObjectResponse.builder().eTag("copy-etag").build();
        }), "ns", "compartment");
        var result = store.copyBlob(software.amazon.awssdk.services.s3.model
                .CopyObjectRequest.builder().sourceBucket("source")
                .sourceKey("key").destinationBucket("destination")
                .destinationKey("key").ifMatch("destination-etag").build());
        assertThat(result.copyObjectResult().eTag()).isEqualTo("copy-etag");
    }

    @Test
    public void existingPublicBucketCannotBeReportedAsPrivate() {
        var store = new OciNativeBlobStore(rawClient((method, arg) -> {
            if ("headBucket".equals(method)) {
                return com.oracle.bmc.objectstorage.responses.HeadBucketResponse
                        .builder().build();
            }
            return com.oracle.bmc.objectstorage.responses.GetBucketResponse
                    .builder().bucket(com.oracle.bmc.objectstorage.model.Bucket
                            .builder().publicAccessType(com.oracle.bmc
                                    .objectstorage.model.Bucket.PublicAccessType
                                            .ObjectRead).build()).build();
        }), "ns", "compartment");
        assertThatThrownBy(() -> store.getContainerAccess("public-bucket"))
                .satisfies(error -> assertThat(S3Exceptions.errorCode(
                        (software.amazon.awssdk.services.s3.model.S3Exception)
                                error)).isEqualTo("NotImplemented"));
        assertThatThrownBy(() -> store.putBlob(PutObjectRequest.builder()
                .bucket("public-bucket").key("key").contentLength(0L)
                .acl(ObjectCannedACL.PRIVATE).build(),
                new ByteArrayInputStream(new byte[0])))
                .satisfies(error -> assertThat(S3Exceptions.errorCode(
                        (software.amazon.awssdk.services.s3.model.S3Exception)
                                error)).isEqualTo("NotImplemented"));
    }

    @Test
    public void sdkNotModifiedFlagsBecomeS3NotModifiedResponses() {
        var store = new OciNativeBlobStore(client((method, arg) -> {
            if ("headObject".equals(method)) {
                return com.oracle.bmc.objectstorage.responses.HeadObjectResponse
                        .builder().isNotModified(true).eTag("etag").build();
            }
            return GetObjectResponse.builder().isNotModified(true)
                    .eTag("etag").build();
        }), "ns", "compartment");
        assertThatThrownBy(() -> store.blobMetadata(HeadObjectRequest.builder()
                .bucket("bucket").key("key").ifNoneMatch("etag").build()))
                .isInstanceOfSatisfying(software.amazon.awssdk.services.s3.model
                        .S3Exception.class, error ->
                        assertThat(error.statusCode()).isEqualTo(304));
        assertThatThrownBy(() -> store.getBlob(GetObjectRequest.builder()
                .bucket("bucket").key("key").ifNoneMatch("etag").build()))
                .isInstanceOfSatisfying(software.amazon.awssdk.services.s3.model
                        .S3Exception.class, error ->
                        assertThat(error.statusCode()).isEqualTo(304));
    }

    @Test
    public void failedCopySourceIfNoneMatchIsPreconditionFailed() {
        var store = new OciNativeBlobStore(client((method, arg) ->
                GetObjectResponse.builder().isNotModified(true).build()),
                "ns", "compartment");
        assertThatThrownBy(() -> store.copyBlob(
                software.amazon.awssdk.services.s3.model.CopyObjectRequest
                        .builder().sourceBucket("source").sourceKey("key")
                        .destinationBucket("dest").destinationKey("key")
                        .copySourceIfNoneMatch("etag").build()))
                .isInstanceOfSatisfying(software.amazon.awssdk.services.s3.model
                        .S3Exception.class, error ->
                        assertThat(error.statusCode()).isEqualTo(412));
    }

    @Test
    public void concealedAuthorization404CannotBecomeSuccessfulDelete() {
        var store = new OciNativeBlobStore(client((method, arg) -> {
            throw new BmcException(404, "NotAuthorizedOrNotFound", "concealed",
                    "test", (Throwable) null);
        }), "ns", "compartment");
        assertThatThrownBy(() -> store.removeBlob(
                software.amazon.awssdk.services.s3.model.DeleteObjectRequest
                        .builder().bucket("bucket").key("key").build()))
                .isInstanceOfSatisfying(software.amazon.awssdk.services.s3.model
                        .S3Exception.class, error ->
                        assertThat(error.statusCode()).isEqualTo(404));
    }

}

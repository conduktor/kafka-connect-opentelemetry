package io.conduktor.connect.otel;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying authentication is enforced on the real gRPC and HTTP receivers.
 */
class OtlpReceiverAuthIntegrationTest {

    private static final String VALID_KEY = "s3cr3t-key";

    private OtlpReceiver receiver;
    private int grpcPort;
    private int httpPort;
    private ManagedChannel grpcChannel;

    @BeforeEach
    void setUp() throws Exception {
        grpcPort = findAvailablePort();
        httpPort = findAvailablePort();

        Map<String, String> props = new HashMap<>();
        props.put("name", "test-auth-receiver");
        props.put("otlp.grpc.enabled", "true");
        props.put("otlp.grpc.port", String.valueOf(grpcPort));
        props.put("otlp.http.enabled", "true");
        props.put("otlp.http.port", String.valueOf(httpPort));
        props.put("otlp.bind.address", "127.0.0.1");
        props.put("otlp.auth.enabled", "true");
        props.put("otlp.auth.methods", "api-key");
        props.put("otlp.auth.api-key", VALID_KEY);

        OpenTelemetrySourceConnectorConfig config = new OpenTelemetrySourceConnectorConfig(props);
        receiver = new OtlpReceiver(config, new OtlpAuthenticator(config, null));
        receiver.start();
        Thread.sleep(200);
    }

    @AfterEach
    void tearDown() {
        if (grpcChannel != null) {
            grpcChannel.shutdownNow();
            try {
                grpcChannel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (receiver != null) {
            receiver.stop();
        }
    }

    private int findAvailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    // ---- gRPC ----

    @Test
    void grpcRejectsRequestWithoutApiKey() {
        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort).usePlaintext().build();
        TraceServiceGrpc.TraceServiceBlockingStub stub = TraceServiceGrpc.newBlockingStub(grpcChannel);

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.export(sampleTrace()));
        assertEquals(io.grpc.Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
        assertEquals(0, receiver.getTracesQueue().size());
    }

    @Test
    void grpcRejectsRequestWithWrongApiKey() {
        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort).usePlaintext().build();
        TraceServiceGrpc.TraceServiceBlockingStub stub = withApiKey(
                TraceServiceGrpc.newBlockingStub(grpcChannel), "wrong");

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.export(sampleTrace()));
        assertEquals(io.grpc.Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
        assertEquals(0, receiver.getTracesQueue().size());
    }

    @Test
    void grpcAcceptsRequestWithValidApiKey() {
        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort).usePlaintext().build();
        TraceServiceGrpc.TraceServiceBlockingStub stub = withApiKey(
                TraceServiceGrpc.newBlockingStub(grpcChannel), VALID_KEY);

        assertNotNull(stub.export(sampleTrace()));
        assertEquals(1, receiver.getTracesQueue().size());
    }

    private TraceServiceGrpc.TraceServiceBlockingStub withApiKey(
            TraceServiceGrpc.TraceServiceBlockingStub stub, String key) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER), key);
        return stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    // ---- HTTP ----

    @Test
    void httpRejectsRequestWithoutApiKey() throws Exception {
        HttpURLConnection conn = post("/v1/traces", null);
        assertEquals(401, conn.getResponseCode());
        assertEquals(0, receiver.getTracesQueue().size());
        conn.disconnect();
    }

    @Test
    void httpRejectsRequestWithWrongApiKey() throws Exception {
        HttpURLConnection conn = post("/v1/traces", "wrong");
        assertEquals(401, conn.getResponseCode());
        assertEquals(0, receiver.getTracesQueue().size());
        conn.disconnect();
    }

    @Test
    void httpAcceptsRequestWithValidApiKey() throws Exception {
        HttpURLConnection conn = post("/v1/traces", VALID_KEY);
        assertEquals(200, conn.getResponseCode());
        assertEquals(1, receiver.getTracesQueue().size());
        conn.disconnect();
    }

    private HttpURLConnection post(String path, String apiKey) throws Exception {
        URL url = new URL("http://127.0.0.1:" + httpPort + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-protobuf");
        if (apiKey != null) {
            conn.setRequestProperty("x-api-key", apiKey);
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(sampleTrace().toByteArray());
        }
        return conn;
    }

    private ExportTraceServiceRequest sampleTrace() {
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(KeyValue.newBuilder()
                                        .setKey("service.name")
                                        .setValue(AnyValue.newBuilder().setStringValue("auth-test").build())
                                        .build())
                                .build())
                        .addScopeSpans(ScopeSpans.newBuilder()
                                .addSpans(Span.newBuilder()
                                        .setTraceId(ByteString.copyFrom(new byte[16]))
                                        .setSpanId(ByteString.copyFrom(new byte[8]))
                                        .setName("test-span")
                                        .build())
                                .build())
                        .build())
                .build();
    }
}

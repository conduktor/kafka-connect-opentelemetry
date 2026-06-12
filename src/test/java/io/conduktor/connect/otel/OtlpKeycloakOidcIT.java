package io.conduktor.connect.otel;

import com.google.protobuf.ByteString;
import com.nimbusds.jose.util.JSONObjectUtils;
import dasniko.testcontainers.keycloak.KeycloakContainer;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end OIDC test against a real Keycloak identity provider running in Docker.
 *
 * <p>Verifies that a bearer token issued by Keycloak (client_credentials grant) is accepted
 * by both the gRPC and HTTP receivers, that the JWKS is discovered from the issuer, and that
 * a forged/garbage token is rejected.
 *
 * <p>Dual-mode: when the {@code OTLP_TEST_KEYCLOAK_ISSUER} environment variable points at an
 * already-running Keycloak realm, that instance is used (handy where Testcontainers cannot
 * reach the Docker daemon). Otherwise a Keycloak container is started via Testcontainers.
 */
class OtlpKeycloakOidcIT {

    private static KeycloakContainer keycloak;
    private static String issuer;
    private static String tokenEndpoint;

    private OtlpReceiver receiver;
    private ManagedChannel grpcChannel;
    private int grpcPort;
    private int httpPort;

    @BeforeAll
    static void resolveEndpoints() {
        String externalIssuer = System.getenv("OTLP_TEST_KEYCLOAK_ISSUER");
        if (externalIssuer != null && !externalIssuer.isBlank()) {
            issuer = externalIssuer;
        } else {
            keycloak = new KeycloakContainer().withRealmImportFile("/keycloak/otlp-realm.json");
            keycloak.start();
            issuer = keycloak.getAuthServerUrl() + "/realms/otlp";
        }
        tokenEndpoint = issuer + "/protocol/openid-connect/token";
    }

    @AfterAll
    static void stopKeycloak() {
        if (keycloak != null) {
            keycloak.stop();
        }
    }

    void startReceiver() throws Exception {
        grpcPort = findAvailablePort();
        httpPort = findAvailablePort();

        Map<String, String> props = new HashMap<>();
        props.put("name", "test-keycloak-receiver");
        props.put("otlp.grpc.enabled", "true");
        props.put("otlp.grpc.port", String.valueOf(grpcPort));
        props.put("otlp.http.enabled", "true");
        props.put("otlp.http.port", String.valueOf(httpPort));
        props.put("otlp.bind.address", "127.0.0.1");
        props.put("otlp.auth.enabled", "true");
        props.put("otlp.auth.methods", "oidc");
        props.put("otlp.auth.oidc.issuer", issuer);

        OpenTelemetrySourceConnectorConfig config = new OpenTelemetrySourceConnectorConfig(props);
        receiver = new OtlpReceiver(config, OtlpAuthenticatorFactory.create(config));
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

    @Test
    void httpAcceptsValidKeycloakToken() throws Exception {
        startReceiver();
        String token = fetchAccessToken();

        HttpURLConnection conn = post("/v1/traces", "Bearer " + token);
        assertEquals(200, conn.getResponseCode());
        assertEquals(1, receiver.getTracesQueue().size());
        conn.disconnect();
    }

    @Test
    void httpRejectsGarbageToken() throws Exception {
        startReceiver();

        HttpURLConnection conn = post("/v1/traces", "Bearer not-a-real-token");
        assertEquals(401, conn.getResponseCode());
        assertEquals(0, receiver.getTracesQueue().size());
        conn.disconnect();
    }

    @Test
    void grpcAcceptsValidKeycloakToken() throws Exception {
        startReceiver();
        String token = fetchAccessToken();

        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort).usePlaintext().build();
        Metadata md = new Metadata();
        md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        TraceServiceGrpc.TraceServiceBlockingStub stub = TraceServiceGrpc.newBlockingStub(grpcChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md));

        assertNotNull(stub.export(sampleTrace()));
        assertEquals(1, receiver.getTracesQueue().size());
    }

    @Test
    void grpcRejectsGarbageToken() throws Exception {
        startReceiver();

        grpcChannel = ManagedChannelBuilder.forAddress("127.0.0.1", grpcPort).usePlaintext().build();
        Metadata md = new Metadata();
        md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer garbage");
        TraceServiceGrpc.TraceServiceBlockingStub stub = TraceServiceGrpc.newBlockingStub(grpcChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md));

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.export(sampleTrace()));
        assertEquals(io.grpc.Status.Code.UNAUTHENTICATED, ex.getStatus().getCode());
        assertEquals(0, receiver.getTracesQueue().size());
    }

    // ---- helpers ----

    private String fetchAccessToken() throws Exception {
        URL url = new URL(tokenEndpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        String body = "grant_type=client_credentials"
                + "&client_id=otlp-client"
                + "&client_secret=otlp-secret";
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, conn.getResponseCode(), "Keycloak token request should succeed");
        String response = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();
        Map<String, Object> json = JSONObjectUtils.parse(response);
        return JSONObjectUtils.getString(json, "access_token");
    }

    private HttpURLConnection post(String path, String authorization) throws Exception {
        URL url = new URL("http://127.0.0.1:" + httpPort + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-protobuf");
        if (authorization != null) {
            conn.setRequestProperty("Authorization", authorization);
        }
        try (OutputStream os = conn.getOutputStream()) {
            os.write(sampleTrace().toByteArray());
        }
        return conn;
    }

    private int findAvailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private ExportTraceServiceRequest sampleTrace() {
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(KeyValue.newBuilder()
                                        .setKey("service.name")
                                        .setValue(AnyValue.newBuilder().setStringValue("keycloak-test").build())
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

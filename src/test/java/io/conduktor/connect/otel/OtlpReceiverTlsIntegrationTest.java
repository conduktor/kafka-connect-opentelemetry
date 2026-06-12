package io.conduktor.connect.otel;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests verifying real TLS termination on the gRPC and HTTP receivers.
 * Uses static self-signed test material under src/test/resources/tls (CN/SAN = 127.0.0.1).
 */
class OtlpReceiverTlsIntegrationTest {

    private OtlpReceiver receiver;
    private ManagedChannel grpcChannel;

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

    private File testResource(String name) throws Exception {
        return Paths.get(getClass().getResource("/tls/" + name).toURI()).toFile();
    }

    private int findAvailablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private OpenTelemetrySourceConnectorConfig tlsConfig(int grpcPort, int httpPort,
                                                         String certPath, String keyPath) {
        Map<String, String> props = new HashMap<>();
        props.put("name", "test-tls-receiver");
        props.put("otlp.grpc.enabled", "true");
        props.put("otlp.grpc.port", String.valueOf(grpcPort));
        props.put("otlp.http.enabled", "true");
        props.put("otlp.http.port", String.valueOf(httpPort));
        props.put("otlp.bind.address", "127.0.0.1");
        props.put("otlp.tls.enabled", "true");
        props.put("otlp.tls.cert.path", certPath);
        props.put("otlp.tls.key.path", keyPath);
        return new OpenTelemetrySourceConnectorConfig(props);
    }

    @Test
    void httpServesOverTls() throws Exception {
        File cert = testResource("cert.pem");
        File key = testResource("key.pem");
        int grpcPort = findAvailablePort();
        int httpPort = findAvailablePort();
        receiver = new OtlpReceiver(
                tlsConfig(grpcPort, httpPort, cert.getAbsolutePath(), key.getAbsolutePath()));
        receiver.start();
        Thread.sleep(200);

        URL url = new URL("https://127.0.0.1:" + httpPort + "/v1/traces");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(trustAllContext().getSocketFactory());
        conn.setHostnameVerifier((h, s) -> true);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-protobuf");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(sampleTrace().toByteArray());
        }

        assertEquals(200, conn.getResponseCode());
        assertEquals(1, receiver.getTracesQueue().size());
        conn.disconnect();
    }

    @Test
    void grpcServesOverTls() throws Exception {
        File cert = testResource("cert.pem");
        File key = testResource("key.pem");
        int grpcPort = findAvailablePort();
        int httpPort = findAvailablePort();
        receiver = new OtlpReceiver(
                tlsConfig(grpcPort, httpPort, cert.getAbsolutePath(), key.getAbsolutePath()));
        receiver.start();
        Thread.sleep(200);

        grpcChannel = NettyChannelBuilder.forAddress("127.0.0.1", grpcPort)
                .sslContext(GrpcSslContexts.forClient().trustManager(cert).build())
                .overrideAuthority("127.0.0.1")
                .build();
        TraceServiceGrpc.TraceServiceBlockingStub stub = TraceServiceGrpc.newBlockingStub(grpcChannel);

        assertNotNull(stub.export(sampleTrace()));
        assertEquals(1, receiver.getTracesQueue().size());
    }

    @Test
    void startFailsFastWhenTlsEnabledWithBrokenCert() throws Exception {
        // A cert/key path that exists but is not a valid PEM must fail at start,
        // never silently fall back to plaintext.
        java.io.File bogusCert = java.io.File.createTempFile("bogus", ".pem");
        java.io.File bogusKey = java.io.File.createTempFile("bogus", ".key");
        bogusCert.deleteOnExit();
        bogusKey.deleteOnExit();
        java.nio.file.Files.writeString(bogusCert.toPath(), "not a certificate");
        java.nio.file.Files.writeString(bogusKey.toPath(), "not a key");

        int grpcPort = findAvailablePort();
        int httpPort = findAvailablePort();
        OtlpReceiver brokenReceiver = new OtlpReceiver(
                tlsConfig(grpcPort, httpPort, bogusCert.getAbsolutePath(), bogusKey.getAbsolutePath()));

        assertThrows(Exception.class, brokenReceiver::start);
        brokenReceiver.stop();
    }

    private static SSLContext trustAllContext() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new java.security.SecureRandom());
        return ctx;
    }

    private ExportTraceServiceRequest sampleTrace() {
        return ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder()
                        .setResource(Resource.newBuilder()
                                .addAttributes(KeyValue.newBuilder()
                                        .setKey("service.name")
                                        .setValue(AnyValue.newBuilder().setStringValue("tls-test").build())
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

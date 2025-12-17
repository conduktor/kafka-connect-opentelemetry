package io.conduktor.connect.otel;

import com.google.protobuf.util.JsonFormat;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceResponse;
import io.opentelemetry.proto.collector.logs.v1.LogsServiceGrpc;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceResponse;
import io.opentelemetry.proto.collector.metrics.v1.MetricsServiceGrpc;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.proto.collector.trace.v1.TraceServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OTLP Receiver that implements both gRPC and HTTP endpoints for receiving
 * OpenTelemetry Protocol (OTLP) telemetry data.
 */
public class OtlpReceiver {
    private static final Logger log = LoggerFactory.getLogger(OtlpReceiver.class);

    private final OpenTelemetrySourceConnectorConfig config;
    private final String messageFormat;
    private final JsonFormat.Printer jsonPrinter;

    // Separate queues for each signal type
    private final BlockingQueue<OtlpMessage> tracesQueue;
    private final BlockingQueue<OtlpMessage> metricsQueue;
    private final BlockingQueue<OtlpMessage> logsQueue;

    // gRPC server
    private Server grpcServer;

    // HTTP server (Netty)
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel httpChannel;

    // Metrics
    private final AtomicLong tracesReceived = new AtomicLong(0);
    private final AtomicLong metricsReceived = new AtomicLong(0);
    private final AtomicLong logsReceived = new AtomicLong(0);
    private final AtomicLong tracesDropped = new AtomicLong(0);
    private final AtomicLong metricsDropped = new AtomicLong(0);
    private final AtomicLong logsDropped = new AtomicLong(0);

    private OpenTelemetryMetrics metrics;

    public OtlpReceiver(OpenTelemetrySourceConnectorConfig config) {
        this.config = config;
        this.messageFormat = config.getMessageFormat();
        this.jsonPrinter = JsonFormat.printer()
                .includingDefaultValueFields()
                .preservingProtoFieldNames();

        int queueSize = config.getMessageQueueSize();
        this.tracesQueue = new LinkedBlockingDeque<>(queueSize);
        this.metricsQueue = new LinkedBlockingDeque<>(queueSize);
        this.logsQueue = new LinkedBlockingDeque<>(queueSize);
    }

    /**
     * Start the OTLP receiver endpoints.
     */
    public void start() throws Exception {
        MDC.put("component", "otlp_receiver");
        log.info("event=otlp_receiver_starting grpc_enabled={} grpc_port={} http_enabled={} http_port={} tls_enabled={} message_format={}",
                config.isGrpcEnabled(), config.getGrpcPort(),
                config.isHttpEnabled(), config.getHttpPort(),
                config.isTlsEnabled(), messageFormat);

        if (config.isGrpcEnabled()) {
            startGrpcServer();
        }

        if (config.isHttpEnabled()) {
            startHttpServer();
        }

        log.info("event=otlp_receiver_started");
        MDC.clear();
    }

    /**
     * Stop the OTLP receiver endpoints.
     */
    public void stop() {
        MDC.put("component", "otlp_receiver");
        log.info("event=otlp_receiver_stopping");

        // Stop gRPC server
        if (grpcServer != null) {
            try {
                grpcServer.shutdown();
                if (!grpcServer.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("event=grpc_server_shutdown_timeout action=forcing_shutdown");
                    grpcServer.shutdownNow();
                }
                log.info("event=grpc_server_stopped");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("event=grpc_server_shutdown_interrupted");
                grpcServer.shutdownNow();
            }
        }

        // Stop HTTP server
        if (httpChannel != null) {
            try {
                httpChannel.close().sync();
                log.info("event=http_server_stopped");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("event=http_server_shutdown_interrupted");
            }
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        }

        log.info("event=otlp_receiver_stopped traces_received={} metrics_received={} logs_received={} " +
                "traces_dropped={} metrics_dropped={} logs_dropped={}",
                tracesReceived.get(), metricsReceived.get(), logsReceived.get(),
                tracesDropped.get(), metricsDropped.get(), logsDropped.get());
        MDC.clear();
    }

    /**
     * Set metrics tracker.
     */
    public void setMetrics(OpenTelemetryMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Get messages from the traces queue.
     */
    public BlockingQueue<OtlpMessage> getTracesQueue() {
        return tracesQueue;
    }

    /**
     * Get messages from the metrics queue.
     */
    public BlockingQueue<OtlpMessage> getMetricsQueue() {
        return metricsQueue;
    }

    /**
     * Get messages from the logs queue.
     */
    public BlockingQueue<OtlpMessage> getLogsQueue() {
        return logsQueue;
    }

    /**
     * Start gRPC server for OTLP.
     */
    private void startGrpcServer() throws Exception {
        int port = config.getGrpcPort();
        String bindAddress = config.getBindAddress();

        NettyServerBuilder serverBuilder = NettyServerBuilder.forAddress(
                new InetSocketAddress(bindAddress, port));

        // Add service implementations
        serverBuilder.addService(new TraceServiceImpl());
        serverBuilder.addService(new MetricsServiceImpl());
        serverBuilder.addService(new LogsServiceImpl());

        // TODO: Add TLS support if enabled
        if (config.isTlsEnabled()) {
            log.warn("event=tls_not_implemented message='TLS support not yet implemented for gRPC'");
            // File certChainFile = new File(config.getTlsCertPath());
            // File privateKeyFile = new File(config.getTlsKeyPath());
            // serverBuilder.useTransportSecurity(certChainFile, privateKeyFile);
        }

        grpcServer = serverBuilder.build().start();
        log.info("event=grpc_server_started bind_address={} port={}", bindAddress, port);
    }

    /**
     * Start HTTP server for OTLP.
     */
    private void startHttpServer() throws Exception {
        int port = config.getHttpPort();
        String bindAddress = config.getBindAddress();

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(10 * 1024 * 1024)); // 10MB max
                        pipeline.addLast(new OtlpHttpHandler());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        ChannelFuture future = bootstrap.bind(bindAddress, port).sync();
        httpChannel = future.channel();
        log.info("event=http_server_started bind_address={} port={}", bindAddress, port);
    }

    /**
     * gRPC service implementation for Traces.
     */
    private class TraceServiceImpl extends TraceServiceGrpc.TraceServiceImplBase {
        @Override
        public void export(ExportTraceServiceRequest request,
                          StreamObserver<ExportTraceServiceResponse> responseObserver) {
            try {
                String message = convertToFormat(request);
                OtlpMessage otlpMessage = new OtlpMessage(OtlpSignalType.TRACES, message);

                boolean added = tracesQueue.offer(otlpMessage);
                if (added) {
                    tracesReceived.incrementAndGet();
                    if (metrics != null) {
                        metrics.incrementTracesReceived();
                    }
                } else {
                    tracesDropped.incrementAndGet();
                    if (metrics != null) {
                        metrics.incrementTracesDropped();
                    }
                    log.warn("event=trace_dropped reason=queue_full queue_size={}", tracesQueue.size());
                }

                responseObserver.onNext(ExportTraceServiceResponse.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (Exception e) {
                log.error("event=trace_export_error error={}", e.getMessage(), e);
                responseObserver.onError(e);
            }
        }
    }

    /**
     * gRPC service implementation for Metrics.
     */
    private class MetricsServiceImpl extends MetricsServiceGrpc.MetricsServiceImplBase {
        @Override
        public void export(ExportMetricsServiceRequest request,
                          StreamObserver<ExportMetricsServiceResponse> responseObserver) {
            try {
                String message = convertToFormat(request);
                OtlpMessage otlpMessage = new OtlpMessage(OtlpSignalType.METRICS, message);

                boolean added = metricsQueue.offer(otlpMessage);
                if (added) {
                    metricsReceived.incrementAndGet();
                    if (metrics != null) {
                        metrics.incrementMetricsReceived();
                    }
                } else {
                    metricsDropped.incrementAndGet();
                    if (metrics != null) {
                        metrics.incrementMetricsDropped();
                    }
                    log.warn("event=metric_dropped reason=queue_full queue_size={}", metricsQueue.size());
                }

                responseObserver.onNext(ExportMetricsServiceResponse.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (Exception e) {
                log.error("event=metrics_export_error error={}", e.getMessage(), e);
                responseObserver.onError(e);
            }
        }
    }

    /**
     * gRPC service implementation for Logs.
     */
    private class LogsServiceImpl extends LogsServiceGrpc.LogsServiceImplBase {
        @Override
        public void export(ExportLogsServiceRequest request,
                          StreamObserver<ExportLogsServiceResponse> responseObserver) {
            try {
                String message = convertToFormat(request);
                OtlpMessage otlpMessage = new OtlpMessage(OtlpSignalType.LOGS, message);

                boolean added = logsQueue.offer(otlpMessage);
                if (added) {
                    logsReceived.incrementAndGet();
                    if (metrics != null) {
                        metrics.incrementLogsReceived();
                    }
                } else {
                    logsDropped.incrementAndGet();
                    if (metrics != null) {
                        metrics.incrementLogsDropped();
                    }
                    log.warn("event=log_dropped reason=queue_full queue_size={}", logsQueue.size());
                }

                responseObserver.onNext(ExportLogsServiceResponse.getDefaultInstance());
                responseObserver.onCompleted();
            } catch (Exception e) {
                log.error("event=logs_export_error error={}", e.getMessage(), e);
                responseObserver.onError(e);
            }
        }
    }

    /**
     * HTTP handler for OTLP endpoints.
     */
    private class OtlpHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String uri = request.uri();

            try {
                if (request.method() != HttpMethod.POST) {
                    sendHttpResponse(ctx, request, HttpResponseStatus.METHOD_NOT_ALLOWED, "Only POST method is supported");
                    return;
                }

                ByteBuf content = request.content();
                byte[] data = new byte[content.readableBytes()];
                content.readBytes(data);

                if (uri.startsWith("/v1/traces")) {
                    handleTraces(ctx, request, data);
                } else if (uri.startsWith("/v1/metrics")) {
                    handleMetrics(ctx, request, data);
                } else if (uri.startsWith("/v1/logs")) {
                    handleLogs(ctx, request, data);
                } else {
                    sendHttpResponse(ctx, request, HttpResponseStatus.NOT_FOUND, "Unknown endpoint: " + uri);
                }
            } catch (Exception e) {
                log.error("event=http_request_error uri={} error={}", uri, e.getMessage(), e);
                sendHttpResponse(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "Error processing request: " + e.getMessage());
            }
        }

        private void handleTraces(ChannelHandlerContext ctx, FullHttpRequest request, byte[] data) {
            try {
                ExportTraceServiceRequest otlpRequest = parseTraceRequest(request, data);
                String message = convertToFormat(otlpRequest);
                OtlpMessage otlpMessage = new OtlpMessage(OtlpSignalType.TRACES, message);

                boolean added = tracesQueue.offer(otlpMessage);
                if (added) {
                    tracesReceived.incrementAndGet();
                    if (metrics != null) {
                        metrics.incrementTracesReceived();
                    }
                    sendHttpResponse(ctx, request, HttpResponseStatus.OK, "{}");
                } else {
                    tracesDropped.incrementAndGet();
                    if (metrics != null) {
                        metrics.incrementTracesDropped();
                    }
                    log.warn("event=trace_dropped reason=queue_full queue_size={}", tracesQueue.size());
                    sendHttpResponse(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE,
                            "{\"error\":\"Queue full\"}");
                }
            } catch (Exception e) {
                log.error("event=http_trace_error error={}", e.getMessage(), e);
                sendHttpResponse(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }

        private ExportTraceServiceRequest parseTraceRequest(FullHttpRequest request, byte[] data) throws Exception {
            String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE, "application/x-protobuf");
            if (contentType.contains("json")) {
                ExportTraceServiceRequest.Builder builder = ExportTraceServiceRequest.newBuilder();
                JsonFormat.parser().ignoringUnknownFields().merge(new String(data, StandardCharsets.UTF_8), builder);
                return builder.build();
            } else {
                return ExportTraceServiceRequest.parseFrom(data);
            }
        }

        private void handleMetrics(ChannelHandlerContext ctx, FullHttpRequest request, byte[] data) {
            try {
                ExportMetricsServiceRequest otlpRequest = parseMetricsRequest(request, data);
                String message = convertToFormat(otlpRequest);
                OtlpMessage otlpMessage = new OtlpMessage(OtlpSignalType.METRICS, message);

                boolean added = metricsQueue.offer(otlpMessage);
                if (added) {
                    metricsReceived.incrementAndGet();
                    if (metrics != null) {
                        metrics.incrementMetricsReceived();
                    }
                    sendHttpResponse(ctx, request, HttpResponseStatus.OK, "{}");
                } else {
                    metricsDropped.incrementAndGet();
                    if (metrics != null) {
                        metrics.incrementMetricsDropped();
                    }
                    log.warn("event=metric_dropped reason=queue_full queue_size={}", metricsQueue.size());
                    sendHttpResponse(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE,
                            "{\"error\":\"Queue full\"}");
                }
            } catch (Exception e) {
                log.error("event=http_metrics_error error={}", e.getMessage(), e);
                sendHttpResponse(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }

        private ExportMetricsServiceRequest parseMetricsRequest(FullHttpRequest request, byte[] data) throws Exception {
            String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE, "application/x-protobuf");
            if (contentType.contains("json")) {
                ExportMetricsServiceRequest.Builder builder = ExportMetricsServiceRequest.newBuilder();
                JsonFormat.parser().ignoringUnknownFields().merge(new String(data, StandardCharsets.UTF_8), builder);
                return builder.build();
            } else {
                return ExportMetricsServiceRequest.parseFrom(data);
            }
        }

        private void handleLogs(ChannelHandlerContext ctx, FullHttpRequest request, byte[] data) {
            try {
                ExportLogsServiceRequest otlpRequest = parseLogsRequest(request, data);
                String message = convertToFormat(otlpRequest);
                OtlpMessage otlpMessage = new OtlpMessage(OtlpSignalType.LOGS, message);

                boolean added = logsQueue.offer(otlpMessage);
                if (added) {
                    logsReceived.incrementAndGet();
                    if (metrics != null) {
                        metrics.incrementLogsReceived();
                    }
                    sendHttpResponse(ctx, request, HttpResponseStatus.OK, "{}");
                } else {
                    logsDropped.incrementAndGet();
                    if (metrics != null) {
                        metrics.incrementLogsDropped();
                    }
                    log.warn("event=log_dropped reason=queue_full queue_size={}", logsQueue.size());
                    sendHttpResponse(ctx, request, HttpResponseStatus.SERVICE_UNAVAILABLE,
                            "{\"error\":\"Queue full\"}");
                }
            } catch (Exception e) {
                log.error("event=http_logs_error error={}", e.getMessage(), e);
                sendHttpResponse(ctx, request, HttpResponseStatus.BAD_REQUEST,
                        "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }

        private ExportLogsServiceRequest parseLogsRequest(FullHttpRequest request, byte[] data) throws Exception {
            String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE, "application/x-protobuf");
            if (contentType.contains("json")) {
                ExportLogsServiceRequest.Builder builder = ExportLogsServiceRequest.newBuilder();
                JsonFormat.parser().ignoringUnknownFields().merge(new String(data, StandardCharsets.UTF_8), builder);
                return builder.build();
            } else {
                return ExportLogsServiceRequest.parseFrom(data);
            }
        }

        private void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest request,
                                      HttpResponseStatus status, String content) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                    request.protocolVersion(), status,
                    Unpooled.copiedBuffer(content, StandardCharsets.UTF_8));

            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());

            if (HttpUtil.isKeepAlive(request)) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }

            ctx.writeAndFlush(response);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("event=http_channel_error error={}", cause.getMessage(), cause);
            ctx.close();
        }
    }

    /**
     * Convert protobuf message to configured format (JSON or protobuf bytes).
     */
    private String convertToFormat(com.google.protobuf.Message message) throws Exception {
        if ("json".equalsIgnoreCase(messageFormat)) {
            return jsonPrinter.print(message);
        } else {
            // For protobuf format, encode as base64
            return java.util.Base64.getEncoder().encodeToString(message.toByteArray());
        }
    }

    /**
     * OTLP message wrapper with signal type.
     */
    public static class OtlpMessage {
        private final OtlpSignalType signalType;
        private final String payload;
        private final long timestamp;

        public OtlpMessage(OtlpSignalType signalType, String payload) {
            this.signalType = signalType;
            this.payload = payload;
            this.timestamp = System.currentTimeMillis();
        }

        public OtlpSignalType getSignalType() {
            return signalType;
        }

        public String getPayload() {
            return payload;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    /**
     * OTLP signal types.
     */
    public enum OtlpSignalType {
        TRACES,
        METRICS,
        LOGS
    }
}

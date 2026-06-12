package io.conduktor.connect.otel;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * gRPC interceptor that authenticates incoming calls using {@link OtlpAuthenticator}.
 * Calls that fail authentication are closed with {@link Status#UNAUTHENTICATED} before
 * reaching the service implementation.
 */
public class OtlpGrpcAuthInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(OtlpGrpcAuthInterceptor.class);

    private final OtlpAuthenticator authenticator;

    public OtlpGrpcAuthInterceptor(OtlpAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        OtlpAuthenticator.Result result = authenticator.authenticate(toHeaderMap(headers));
        if (!result.isAuthenticated()) {
            log.warn("event=grpc_auth_rejected method={} reason={}",
                    call.getMethodDescriptor().getFullMethodName(), result.getFailureReason());
            call.close(Status.UNAUTHENTICATED.withDescription("Authentication failed"), new Metadata());
            return new ServerCall.Listener<ReqT>() {
            };
        }
        return next.startCall(call, headers);
    }

    private static Map<String, String> toHeaderMap(Metadata headers) {
        Map<String, String> map = new HashMap<>();
        for (String key : headers.keys()) {
            if (key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
                continue; // skip binary metadata; auth uses ASCII headers only
            }
            String value = headers.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
            if (value != null) {
                map.put(key, value);
            }
        }
        return map;
    }
}

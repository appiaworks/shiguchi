package dev.appiaworks.shiguchi.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.protobuf.Any;
import com.google.rpc.BadRequest;
import graphql.GraphQLError;
import graphql.Scalars;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.language.SourceLocation;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GrpcGraphQlExceptionHandler")
class GrpcGraphQlExceptionHandlerTest {

    private static DataFetchingEnvironment env() {
        Field field = Field.newField("getBook")
                .sourceLocation(new SourceLocation(1, 3))
                .build();
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .mergedField(MergedField.newMergedField(field).build())
                .executionStepInfo(ExecutionStepInfo.newExecutionStepInfo()
                        .type(Scalars.GraphQLString)
                        .path(ResultPath.rootPath().segment("getBook"))
                        .build())
                .build();
    }

    private static DataFetcherExceptionHandlerParameters params(Throwable t) {
        return DataFetcherExceptionHandlerParameters.newExceptionParameters()
                .dataFetchingEnvironment(env())
                .exception(t)
                .build();
    }

    private static GraphQLError single(DataFetcherExceptionHandler handler, Throwable t) throws Exception {
        DataFetcherExceptionHandlerResult result =
                handler.handleException(params(t)).get();
        assertThat(result.getErrors()).hasSize(1);
        return result.getErrors().get(0);
    }

    @Test
    @DisplayName(
            "GIVEN a GrpcBackendException carrying a BadRequest WHEN handled SHOULD produce one error with the description as message and code, service, method and details in extensions")
    void given_backendExceptionWithDetails_when_handled_should_exposeGrpcSemanticsInExtensions() throws Exception {
        BadRequest badRequest = BadRequest.newBuilder()
                .addFieldViolations(
                        BadRequest.FieldViolation.newBuilder().setField("id").setDescription("must be numeric"))
                .build();
        StatusRuntimeException sre = StatusProto.toStatusRuntimeException(com.google.rpc.Status.newBuilder()
                .setCode(Status.Code.INVALID_ARGUMENT.value())
                .setMessage("bad id")
                .addDetails(Any.pack(badRequest))
                .build());
        Throwable wrapped =
                new RuntimeException("fetch failed", new GrpcBackendException("lib.Library", "GetBook", sre, null));

        GraphQLError error = single(new GrpcGraphQlExceptionHandler(mock(DataFetcherExceptionHandler.class)), wrapped);

        assertThat(error.getMessage()).isEqualTo("bad id");
        assertThat(error.getPath()).containsExactly("getBook");
        assertThat(error.getLocations()).hasSize(1);
        Map<String, Object> ext = error.getExtensions();
        assertThat(ext)
                .containsEntry("errorType", "BAD_REQUEST")
                .containsEntry("grpcCode", "INVALID_ARGUMENT")
                .containsEntry("service", "lib.Library")
                .containsEntry("method", "GetBook");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) ext.get("details");
        assertThat(details).hasSize(1);
        assertThat(details.get(0)).containsEntry("@type", "type.googleapis.com/google.rpc.BadRequest");
    }

    @Test
    @DisplayName(
            "GIVEN a bare StatusRuntimeException without details WHEN handled SHOULD map the code and omit details")
    void given_bareStatusException_when_handled_should_mapCodeWithoutDetails() throws Exception {
        GraphQLError error = single(
                new GrpcGraphQlExceptionHandler(mock(DataFetcherExceptionHandler.class)),
                Status.NOT_FOUND.withDescription("no such book").asRuntimeException());

        assertThat(error.getMessage()).isEqualTo("no such book");
        assertThat(error.getExtensions())
                .containsEntry("errorType", "NOT_FOUND")
                .containsEntry("grpcCode", "NOT_FOUND")
                .doesNotContainKeys("details", "service");
    }

    @Test
    @DisplayName("GIVEN a status without description WHEN handled SHOULD use the code name as the message")
    void given_statusWithoutDescription_when_handled_should_useCodeAsMessage() throws Exception {
        GraphQLError error = single(
                new GrpcGraphQlExceptionHandler(mock(DataFetcherExceptionHandler.class)),
                Status.UNAVAILABLE.asRuntimeException());

        assertThat(error.getMessage()).isEqualTo("UNAVAILABLE");
        assertThat(error.getExtensions()).containsEntry("errorType", "UNAVAILABLE");
    }

    @Test
    @DisplayName(
            "GIVEN an exception with no gRPC status in its cause chain WHEN handled SHOULD delegate to the fallback handler untouched")
    void given_nonGrpcException_when_handled_should_delegateToFallback() throws Exception {
        DataFetcherExceptionHandler fallback = mock(DataFetcherExceptionHandler.class);
        DataFetcherExceptionHandlerResult fallbackResult =
                DataFetcherExceptionHandlerResult.newResult().build();
        when(fallback.handleException(org.mockito.ArgumentMatchers.any()))
                .thenReturn(CompletableFuture.completedFuture(fallbackResult));
        DataFetcherExceptionHandlerParameters p = params(new IllegalStateException("boom"));

        DataFetcherExceptionHandlerResult result =
                new GrpcGraphQlExceptionHandler(fallback).handleException(p).get();

        assertThat(result).isSameAs(fallbackResult);
        verify(fallback).handleException(p);
    }

    @Test
    @DisplayName("GIVEN every gRPC code WHEN classified SHOULD map onto a DGS ErrorType name")
    void given_everyCode_when_classified_should_mapOntoErrorType() {
        assertThat(GrpcGraphQlExceptionHandler.errorType(Status.Code.INVALID_ARGUMENT))
                .isEqualTo("BAD_REQUEST");
        assertThat(GrpcGraphQlExceptionHandler.errorType(Status.Code.PERMISSION_DENIED))
                .isEqualTo("PERMISSION_DENIED");
        assertThat(GrpcGraphQlExceptionHandler.errorType(Status.Code.UNAUTHENTICATED))
                .isEqualTo("UNAUTHENTICATED");
        assertThat(GrpcGraphQlExceptionHandler.errorType(Status.Code.ALREADY_EXISTS))
                .isEqualTo("FAILED_PRECONDITION");
        assertThat(GrpcGraphQlExceptionHandler.errorType(Status.Code.DEADLINE_EXCEEDED))
                .isEqualTo("UNAVAILABLE");
        assertThat(GrpcGraphQlExceptionHandler.errorType(Status.Code.INTERNAL)).isEqualTo("INTERNAL");
        assertThat(GrpcGraphQlExceptionHandler.errorType(Status.Code.UNIMPLEMENTED))
                .isEqualTo("INTERNAL");
        assertThat(GrpcGraphQlExceptionHandler.errorType(Status.Code.UNKNOWN)).isEqualTo("UNKNOWN");
        verifyNoInteractions(mock(DataFetcherExceptionHandler.class));
    }
}

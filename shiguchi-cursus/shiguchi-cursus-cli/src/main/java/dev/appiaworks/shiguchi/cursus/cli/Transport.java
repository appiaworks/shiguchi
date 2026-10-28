package dev.appiaworks.shiguchi.cursus.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

/** One way of holding a subscription open: SSE, WebSocket or gRPC. */
interface Transport {

    /**
     * Connects once and streams until the server ends the stream, the sink has had enough or the
     * lifecycle is stopped.
     */
    End stream(SubRequest request, EventSink sink, Lifecycle lifecycle) throws Exception;

    /** What a subscription asks for. {@code after} is one resume id; WebSocket may use the per-topic map. */
    record SubRequest(
            List<String> topics,
            String after,
            Map<String, String> afterByTopic,
            String group,
            String member,
            String session,
            String qos,
            boolean ack,
            String origin) {

        /** Without an origin of its own. */
        SubRequest(
                List<String> topics,
                String after,
                Map<String, String> afterByTopic,
                String group,
                String member,
                String session,
                String qos,
                boolean ack) {
            this(topics, after, afterByTopic, group, member, session, qos, ack, null);
        }

        SubRequest resumeFrom(String id, Map<String, String> byTopic) {
            return new SubRequest(topics, id, byTopic, group, member, session, qos, ack, origin);
        }
    }

    /** Receives what a transport reads. */
    interface EventSink {

        /** An event as {@code {topic, id, data, attributes?, key?, time?}}; false when the command has seen enough. */
        boolean event(ObjectNode event);

        /** A control signal worth printing: {@code reset}, {@code overflow}. */
        void control(String kind, JsonNode body);
    }

    /** How a connection ended. */
    record End(Kind kind, String reason) {

        enum Kind {
            /** The server ended the stream on purpose ({@code event: close}, lifetime, shutdown). */
            CLOSED,
            /** The client's queue filled; resume with the last id. */
            OVERFLOW,
            /** The server cannot resume from the id; start again. */
            RESET,
            /** The connection ended without a word. */
            EOF,
            /** The sink or the lifecycle ended it. */
            DONE
        }

        static End of(Kind kind, String reason) {
            return new End(kind, reason);
        }
    }
}

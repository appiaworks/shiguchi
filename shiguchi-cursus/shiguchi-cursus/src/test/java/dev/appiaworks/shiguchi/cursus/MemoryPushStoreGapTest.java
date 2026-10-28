package dev.appiaworks.shiguchi.cursus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("In-memory push store: the highest id the history dropped")
class MemoryPushStoreGapTest {

    private static PushGapStoreContract.Engine engine() {
        MemoryPushStore store = new MemoryPushStore();
        return new PushGapStoreContract.Engine(
                store, null, (topic, id) -> store.redact(topic, id).block());
    }

    @Test
    @DisplayName("GIVEN a topic where nothing was removed WHEN asking SHOULD answer empty")
    void given_nothingRemoved_when_askingTrimmedThrough_should_beEmpty() {
        PushGapStoreContract.nothingTrimmed(engine());
    }

    @Test
    @DisplayName(
            "GIVEN more events than the history keeps WHEN asking SHOULD answer the last trimmed id, a resume at it is no gap")
    void given_sizeTrim_when_askingTrimmedThrough_should_answerLastTrimmedId() {
        PushGapStoreContract.trimmedBySize(engine());
    }

    @Test
    @DisplayName("GIVEN events past their ttl WHEN a newer one is appended SHOULD answer the last expired id")
    void given_expiredEvents_when_askingTrimmedThrough_should_answerLastExpiredId() throws InterruptedException {
        PushGapStoreContract.expiredByTtl(engine());
    }

    @Test
    @DisplayName("GIVEN a redacted event WHEN asking SHOULD not count it as removed")
    void given_redactedEvent_when_askingTrimmedThrough_should_notCountIt() {
        PushGapStoreContract.redactedIsNotAGap(engine());
    }
}

package dev.appiaworks.shiguchi.cursus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("In-memory push store: origin attribute and session skip")
class MemoryPushStoreOriginTest {

    @Test
    @DisplayName(
            "GIVEN events stamped with origins WHEN replayed and a session declaring one starts SHOULD keep push.origin through range and move the cursor over the leading own-origin run")
    void given_stamped_events_when_replayed_and_session_starts_should_keep_origin_and_skip_leading_run() {
        PushOriginStoreContract.verify(new MemoryPushStore());
    }
}

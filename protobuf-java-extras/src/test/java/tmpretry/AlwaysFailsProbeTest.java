package tmpretry;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AlwaysFailsProbeTest {
    @Test
    void alwaysFails() {
        Assertions.fail("always");
    }
}

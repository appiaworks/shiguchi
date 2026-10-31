package tmpretry;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ManyFailuresProbeTest {
    @Test
    void fails1() {
        Assertions.fail("many");
    }

    @Test
    void fails2() {
        Assertions.fail("many");
    }

    @Test
    void fails3() {
        Assertions.fail("many");
    }

    @Test
    void fails4() {
        Assertions.fail("many");
    }

    @Test
    void fails5() {
        Assertions.fail("many");
    }

    @Test
    void fails6() {
        Assertions.fail("many");
    }
}

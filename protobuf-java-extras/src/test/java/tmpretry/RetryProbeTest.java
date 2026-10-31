package tmpretry;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RetryProbeTest {
    @Test
    void failsOnce() throws Exception {
        Path marker = Path.of("build/tmp/retry-once");
        if (!Files.exists(marker)) {
            Files.createDirectories(marker.getParent());
            Files.createFile(marker);
            Assertions.fail("first run");
        }
    }
}

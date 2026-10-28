package dev.appiaworks.shiguchi.cursus;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

@DisplayName("Push republish jobs")
class PushRepublishJobsTest {

    private static final int FAST = 100_000;

    private final PushRepublishJobs jobs = new PushRepublishJobs();

    @Test
    @DisplayName("GIVEN three archived events WHEN republished as a job SHOULD finish done with every event published")
    void given_three_archived_events_when_republished_should_finish_done_with_all_published()
            throws InterruptedException {
        List<PushArchive.Entry> events = List.of(
                archived("0000000000000000001"), archived("0000000000000000002"), archived("0000000000000000003"));

        PushRepublishJobs.Job job = jobs.start("alice", null, "fix", events, PushRepublishJobsTest::republished, FAST);
        awaitFinished(job);

        assertThat(job.state()).isEqualTo(PushRepublishJobs.State.DONE);
        assertThat(job.published()).isEqualTo(3);
        assertThat(job.failed()).isZero();
        assertThat(job.matched()).isEqualTo(3);
    }

    @Test
    @DisplayName("GIVEN a publish that fails for one event WHEN the job runs SHOULD count it as failed and finish done")
    void given_publish_failing_for_one_event_when_job_runs_should_count_failure_and_finish_done()
            throws InterruptedException {
        List<PushArchive.Entry> events = List.of(
                archived("0000000000000000001"), archived("0000000000000000002"), archived("0000000000000000003"));

        PushRepublishJobs.Job job = jobs.start(
                "alice",
                null,
                null,
                events,
                entry -> {
                    if (entry.event().id().endsWith("2")) return Mono.error(new IllegalStateException("broker down"));
                    return republished(entry);
                },
                FAST);
        awaitFinished(job);

        assertThat(job.state()).isEqualTo(PushRepublishJobs.State.DONE);
        assertThat(job.failed()).isEqualTo(1);
        assertThat(job.published()).isEqualTo(2);
    }

    @Test
    @DisplayName("GIVEN no events WHEN a job starts SHOULD be done at once with nothing matched")
    void given_no_events_when_job_starts_should_be_done_at_once() {
        PushRepublishJobs.Job job =
                jobs.start("alice", null, null, List.of(), PushRepublishJobsTest::republished, FAST);

        assertThat(job.state()).isEqualTo(PushRepublishJobs.State.DONE);
        assertThat(job.matched()).isZero();
    }

    @Test
    @DisplayName("GIVEN a running job WHEN cancelled SHOULD become cancelled and a second cancel SHOULD be refused")
    void given_running_job_when_cancelled_should_become_cancelled_and_second_cancel_refused() {
        PushRepublishJobs.Job job =
                jobs.start("alice", null, null, List.of(archived("0000000000000000001")), entry -> Mono.never(), FAST);

        assertThat(jobs.cancel(job.id())).isTrue();
        assertThat(job.state()).isEqualTo(PushRepublishJobs.State.CANCELLED);
        assertThat(jobs.cancel(job.id())).isFalse();
    }

    @Test
    @DisplayName("GIVEN two jobs started WHEN listed SHOULD return the newest first")
    void given_two_jobs_when_listed_should_return_newest_first() {
        PushRepublishJobs.Job first =
                jobs.start("alice", null, null, List.of(), PushRepublishJobsTest::republished, FAST);
        PushRepublishJobs.Job second =
                jobs.start("alice", null, null, List.of(), PushRepublishJobsTest::republished, FAST);

        assertThat(jobs.list()).extracting(PushRepublishJobs.Job::id).containsExactly(second.id(), first.id());
    }

    @Test
    @DisplayName("GIVEN an unknown job id WHEN looked up SHOULD return null")
    void given_unknown_job_id_when_looked_up_should_return_null() {
        assertThat(jobs.get("no-such-job")).isNull();
    }

    @Test
    @DisplayName("GIVEN a job WHEN rendered SHOULD expose its progress keys")
    void given_job_when_rendered_should_expose_progress_keys() {
        PushRepublishJobs.Job job =
                jobs.start("alice", null, null, List.of(), PushRepublishJobsTest::republished, FAST);

        Map<String, Object> view = job.toMap();

        assertThat(view).containsKeys("id", "state", "matched", "published", "failed", "startedAt");
    }

    private static PushArchive.Entry archived(String id) {
        PushEvent event = new PushEvent(
                Topic.parse("orders:1"), id, "{}".getBytes(StandardCharsets.UTF_8), Map.of(), null, Instant.now());
        return new PushArchive.Entry(event, null, null);
    }

    private static Mono<PushEvent> republished(PushArchive.Entry entry) {
        return Mono.just(entry.event().withId("new-" + entry.event().id()));
    }

    /** Polls until the job leaves RUNNING, for up to five seconds. */
    private static void awaitFinished(PushRepublishJobs.Job job) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (job.state() == PushRepublishJobs.State.RUNNING && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }
}

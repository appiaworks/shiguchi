package dev.appiaworks.shiguchi.sample.policy;

import org.springframework.scheduling.annotation.Scheduled;

/** Calls {@link FileBackedPolicy#reloadIfChanged()} on a fixed delay. */
class PolicyReloader {

    private final FileBackedPolicy policy;

    PolicyReloader(FileBackedPolicy policy) {
        this.policy = policy;
    }

    @Scheduled(fixedDelayString = "${sample.policy.reload-interval:2s}")
    void tick() {
        policy.reloadIfChanged();
    }
}

package dev.appiaworks.shiguchi.discovery.client;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.ClassUtils;

/**
 * Keeps the instance {@code STARTING} until its gRPC port is published.
 *
 * <p>Eureka registers the instance {@code UP} as soon as the app starts, but a server on port 0 only
 * knows its port after it binds. A gateway reading the registry in that window finds no
 * {@code grpc-port} and falls back to the HTTP port. While the hold is engaged the instance is
 * registered {@code STARTING} (see {@code GrpcPortRegistrationAutoConfiguration}) and
 * {@link #guard(HealthCheckHandler)} stops Spring Cloud's health check handler from flipping it to
 * {@code UP}. {@link #release(ApplicationInfoManager)} lets it go, and the timeout releases it
 * anyway, so a missing port never leaves the instance out of rotation for good.
 */
public class GrpcPortHold implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GrpcPortHold.class);

    /** Runs a task once after a delay; the returned runnable cancels it. Replaceable so tests need no real time. */
    @FunctionalInterface
    public interface Scheduler {
        Runnable schedule(Runnable task, Duration delay);
    }

    private final DiscoveryClientProperties properties;
    private final ObjectProvider<ApplicationInfoManager> infoManager;
    private final Scheduler scheduler;
    private final AtomicBoolean holding = new AtomicBoolean();
    private volatile Runnable cancelTimeout = () -> {};
    private volatile ScheduledExecutorService ownExecutor;

    public GrpcPortHold(
            DiscoveryClientProperties properties,
            ObjectProvider<ApplicationInfoManager> infoManager,
            Scheduler scheduler) {
        this.properties = properties;
        this.infoManager = infoManager;
        this.scheduler = scheduler != null ? scheduler : this::scheduleOnOwnThread;
    }

    /** Whether the instance is currently held out of rotation. */
    public boolean isHolding() {
        return holding.get();
    }

    /** Engages the hold; called while the instance config is built, before it registers. */
    void engage() {
        holding.set(true);
    }

    /**
     * Starts the timeout, once the app is ready and the port is still unknown. A no-op when nothing is
     * held or the timer is already running.
     */
    void startTimeout() {
        if (!holding.get()) return;
        Duration timeout = properties.getPublishTimeout();
        if (timeout == null || timeout.isNegative()) return;
        cancelTimeout.run();
        cancelTimeout = scheduler.schedule(this::onTimeout, timeout);
    }

    private void onTimeout() {
        if (!holding.get()) return;
        log.warn(
                "No gRPC port was published within {}: releasing the Eureka instance WITHOUT grpc-port, so a"
                        + " gateway falls back to the HTTP port. Set shiguchi.discovery.client.grpc-port, declare a"
                        + " GrpcServerPortSupplier bean, call GrpcPortPublisher.publish(port), or turn the hold off"
                        + " with shiguchi.discovery.client.hold-until-published=false",
                properties.getPublishTimeout());
        release(infoManager.getIfAvailable());
    }

    /**
     * Ends the hold and moves the instance from {@code STARTING} to {@code UP}. The caller has already
     * written the port into the metadata, so the one registration that follows carries both. Only a
     * {@code STARTING} instance moves: a {@code DOWN} or {@code OUT_OF_SERVICE} one stays as it is.
     */
    void release(ApplicationInfoManager manager) {
        if (!holding.compareAndSet(true, false)) return;
        cancelTimeout.run();
        if (manager != null) {
            manager.setInstanceStatus(InstanceStatus.STARTING, InstanceStatus.UP);
        }
    }

    /**
     * Wraps the app's health check handler so that, while held, a health of {@code UP} does not move the
     * instance out of {@code STARTING}. Anything else (a {@code DOWN}) passes through.
     */
    HealthCheckHandler guard(HealthCheckHandler delegate) {
        ProxyFactory factory = new ProxyFactory(delegate);
        factory.setProxyTargetClass(false);
        // Every interface of the handler stays (Lifecycle, Ordered...), so Spring's own lifecycle handling
        // of EurekaHealthCheckHandler (stop on shutdown) keeps working on the proxy.
        factory.setInterfaces(ClassUtils.getAllInterfaces(delegate));
        factory.addAdvice((MethodInterceptor) invocation -> {
            Object result = invocation.proceed();
            if (isStatusCall(invocation.getMethod()) && holding.get() && result == InstanceStatus.UP) {
                return null;
            }
            return result;
        });
        return (HealthCheckHandler) factory.getProxy(getClass().getClassLoader());
    }

    private static boolean isStatusCall(Method method) {
        return method.getName().equals("getStatus")
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == InstanceStatus.class;
    }

    private Runnable scheduleOnOwnThread(Runnable task, Duration delay) {
        ScheduledExecutorService executor = ownExecutor;
        if (executor == null) {
            synchronized (this) {
                if (ownExecutor == null) {
                    ownExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "shiguchi-grpc-port-hold");
                        t.setDaemon(true);
                        return t;
                    });
                }
                executor = ownExecutor;
            }
        }
        ScheduledFuture<?> future = executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void close() {
        cancelTimeout.run();
        ScheduledExecutorService executor = ownExecutor;
        if (executor != null) executor.shutdownNow();
    }
}

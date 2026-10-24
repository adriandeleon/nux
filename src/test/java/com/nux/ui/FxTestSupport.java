package com.nux.ui;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

/**
 * Boots the JavaFX toolkit once per JVM for the UI tests.
 *
 * <p>Runs on JavaFX 27's built-in <em>Headless</em> Glass platform (selected by the
 * surefire system properties), so no display, no Xvfb, and — because
 * {@link Platform#startup} is all that is needed — no TestFX dependency either.
 * Tests never drive a robot; they call into the scene graph directly on the FX thread.
 */
public final class FxTestSupport {

    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final long TIMEOUT_SECONDS = 20;

    private FxTestSupport() {}

    public static void startToolkit() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyRunning) {
            ready.countDown();
        }
        await(ready);
        // Windows opened and closed by tests must not shut the toolkit down.
        runOnFx(() -> Platform.setImplicitExit(false));
        // The app's theme, as App.start sets it: without it the tests render unstyled and
        // nux.css's -color-* lookups fail (dozens of "could not resolve" warnings).
        runOnFx(() -> javafx.application.Application.setUserAgentStylesheet(
                new atlantafx.base.theme.PrimerDark().getUserAgentStylesheet()));
    }

    /** Runs on the FX thread and waits, rethrowing whatever the action threw. */
    public static void runOnFx(Runnable action) {
        callOnFx(() -> {
            action.run();
            return null;
        });
    }

    /** Runs on the FX thread and returns its result, rethrowing failures to the test. */
    public static <T> T callOnFx(Callable<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        await(done);
        Throwable thrown = failure.get();
        if (thrown != null) {
            throw thrown instanceof RuntimeException re ? re : new IllegalStateException(thrown);
        }
        return result.get();
    }

    /** Lets queued FX work (listeners, runLater callbacks) drain before asserting. */
    public static void settle() {
        runOnFx(() -> {});
        runOnFx(() -> {});
    }

    /** Reads a private field — the UI classes expose their labels no other way. */
    @SuppressWarnings("unchecked")
    public static <T> T field(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return (T) field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot read " + name, e);
            }
        }
        throw new IllegalArgumentException("no field " + name + " on " + target.getClass());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the FX thread");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the FX thread", e);
        }
    }
}

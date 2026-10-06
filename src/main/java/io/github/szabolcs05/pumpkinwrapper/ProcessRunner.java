/*
 * Copyright (C) 2026 Szabolcs05
 * SPDX-License-Identifier: GPL-3.0-only
 */
package io.github.szabolcs05.pumpkinwrapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Runs the native server while keeping the Java launcher attached to its console. */
final class ProcessRunner {
    private ProcessRunner() {
    }

    static int run(List<String> command, Path directory, int shutdownTimeoutSeconds)
            throws IOException, InterruptedException {
        return run(command, directory, shutdownTimeoutSeconds, System.in,
                ProcessBuilder.Redirect.INHERIT, ProcessBuilder.Redirect.INHERIT);
    }

    // Redirects and an input source make the same process lifecycle testable without changing System.in.
    static int run(List<String> command, Path directory, int shutdownTimeoutSeconds,
                   InputStream input, ProcessBuilder.Redirect stdout, ProcessBuilder.Redirect stderr)
            throws IOException, InterruptedException {
        if (shutdownTimeoutSeconds < 1) {
            throw new IllegalArgumentException("Shutdown timeout must be at least one second");
        }
        Objects.requireNonNull(input, "input");
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command))
                .directory(Objects.requireNonNull(directory, "directory").toFile())
                .redirectOutput(stdout)
                .redirectError(stderr);
        Lifecycle lifecycle = new Lifecycle(shutdownTimeoutSeconds);
        Thread hook = new Thread(lifecycle::shutdown, "pumpkin-wrapper-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            Process child = lifecycle.start(builder);
            Thread console = new Thread(() -> forwardInput(input, child.getOutputStream(), lifecycle),
                    "pumpkin-wrapper-console");
            console.setDaemon(true);
            console.start();
            return child.waitFor();
        } finally {
            // Also clean up if waiting is interrupted or starting the console thread fails.
            // A concurrent JVM hook waits for this bounded cleanup through shutdownLock.
            lifecycle.shutdown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown has already begun; the registered hook owns its remaining lifetime.
            }
        }
    }

    private static void forwardInput(InputStream input, OutputStream childInput, Lifecycle lifecycle) {
        byte[] buffer = new byte[4096];
        try {
            while (lifecycle.forwardInput) {
                int count = input.read(buffer);
                if (count < 0 || !lifecycle.forwardInput) {
                    return;
                }
                if (count > 0) {
                    childInput.write(buffer, 0, count);
                    childInput.flush();
                }
            }
        } catch (IOException ignored) {
            // The server may close its console before exiting.
        }
        // Neither System.in nor the child's stdin is closed here. Keeping the latter open at EOF
        // lets the shutdown hook send "stop" even on a host with no interactive console.
    }

    private static final class Lifecycle {
        private static final byte[] STOP = "\nstop\n".getBytes(StandardCharsets.UTF_8);
        private final Object startupLock = new Object();
        private final Object shutdownLock = new Object();
        private final int shutdownTimeoutSeconds;
        private volatile boolean forwardInput = true;
        private boolean shuttingDown;
        private Process child;

        Lifecycle(int shutdownTimeoutSeconds) {
            this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
        }

        Process start(ProcessBuilder builder) throws IOException {
            synchronized (startupLock) {
                if (shuttingDown) {
                    throw new IOException("The JVM is shutting down; Pumpkin was not started");
                }
                // The hook cannot finish between native process creation and publishing its handle.
                child = builder.start();
                return child;
            }
        }

        void shutdown() {
            synchronized (shutdownLock) {
                Process process;
                synchronized (startupLock) {
                    shuttingDown = true;
                    forwardInput = false;
                    process = child;
                }
                if (process == null || !process.isAlive()) {
                    return;
                }

                // Writing to a full stdin pipe can block. The hook must never wait for that lock.
                Thread stopWriter = new Thread(() -> {
                    try {
                        OutputStream stdin = process.getOutputStream();
                        stdin.write(STOP);
                        stdin.flush();
                    } catch (IOException ignored) {
                        // The server may already be shutting down.
                    }
                }, "pumpkin-wrapper-stop");
                stopWriter.setDaemon(true);
                stopWriter.start();

                boolean interrupted = false;
                try {
                    try {
                        if (process.waitFor(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
                            return;
                        }
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                    // Process.destroy() also closes streams, which can contend with a blocked writer.
                    // ProcessHandle sends the OS signal directly without acquiring stdin's monitor.
                    process.toHandle().destroy();
                    try {
                        if (process.waitFor(2, TimeUnit.SECONDS)) {
                            return;
                        }
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                    process.toHandle().destroyForcibly();
                    try {
                        process.waitFor(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                } finally {
                    if (process.isAlive()) {
                        process.toHandle().destroyForcibly();
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }
}

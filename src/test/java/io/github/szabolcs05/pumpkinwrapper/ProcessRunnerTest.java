/*
 * Copyright (C) 2026 Szabolcs05
 * SPDX-License-Identifier: GPL-3.0-only
 */
package io.github.szabolcs05.pumpkinwrapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class ProcessRunnerTest {
    @TempDir
    Path directory;

    @Test
    void forwardsConsoleArgumentsWorkingDirectoryAndExactExitCode() throws Exception {
        Path stdout = directory.resolve("stdout.txt");
        Path stderr = directory.resolve("stderr.txt");
        TrackedInput input = new TrackedInput("say hello\n".getBytes(StandardCharsets.UTF_8));
        String argument = "spaces ; & $() ' stay literal";

        int exitCode = ProcessRunner.run(javaCommand("exercise", argument), directory, 1, input,
                ProcessBuilder.Redirect.to(stdout.toFile()), ProcessBuilder.Redirect.to(stderr.toFile()));

        assertEquals(37, exitCode);
        String output = Files.readString(stdout);
        assertTrue(output.contains("cwd=" + directory.toRealPath()), output);
        assertTrue(output.contains("arg=" + argument), output);
        assertTrue(output.contains("input=say hello"), output);
        assertTrue(Files.readString(stderr).contains("fixture-error"));
        assertFalse(input.closed, "The caller's console stream must remain open");
    }

    @Test
    void rejectsInvalidShutdownTimeoutBeforeStarting() {
        assertThrows(IllegalArgumentException.class,
                () -> ProcessRunner.run(javaCommand("exercise"), directory, 0));
    }

    @Test
    void propagatesStartupFailure() {
        assertThrows(IOException.class,
                () -> ProcessRunner.run(List.of(directory.resolve("missing-executable").toString()), directory, 1));
    }

    @Test
    void interruptingTheWaitStopsTheChildBeforeReturning() throws Exception {
        Path ready = directory.resolve("ready");
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                ProcessRunner.run(javaCommand("await-stop", directory.toString()), directory, 1,
                        InputStream.nullInputStream(), ProcessBuilder.Redirect.DISCARD,
                        ProcessBuilder.Redirect.DISCARD);
            } catch (Throwable failure) {
                result.set(failure);
            }
        });
        runner.setDaemon(true);
        runner.start();
        try {
            await(() -> Files.exists(ready), Duration.ofSeconds(8));
            runner.interrupt();
            runner.join(8000);
            assertFalse(runner.isAlive(), "Interrupted process wait did not finish");
            assertTrue(result.get() instanceof InterruptedException, "The interruption was not propagated");
            assertTrue(Files.exists(directory.resolve("stopped")), "Interrupted wait did not stop the child");
            assertChildExited(ready);
        } finally {
            runner.interrupt();
            if (Files.exists(ready)) {
                ProcessHandle.of(Long.parseLong(Files.readString(ready))).ifPresent(ProcessHandle::destroyForcibly);
            }
            runner.join(2000);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void jvmTerminationSendsStopEvenAfterInputEof() throws Exception {
        Path stopped = directory.resolve("stopped");
        Path ready = directory.resolve("ready");
        Process launcher = new ProcessBuilder(javaCommand("launcher", directory.toString(), "await-stop"))
                .redirectOutput(directory.resolve("launcher-out.txt").toFile())
                .redirectError(directory.resolve("launcher-err.txt").toFile())
                .start();
        try {
            // EOF must not close the server's stdin, since the hook needs it later.
            launcher.getOutputStream().close();
            await(() -> Files.exists(ready), Duration.ofSeconds(8));
            launcher.destroy();
            assertTrue(launcher.waitFor(8, TimeUnit.SECONDS), "The wrapper did not finish shutdown");
            assertTrue(Files.exists(stopped), "The native child did not receive the stop command");
            assertChildExited(ready);
        } finally {
            killFixture(launcher, ready);
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void shutdownRemainsBoundedWhenChildDoesNotReadItsFullInputPipe() throws Exception {
        Path ready = directory.resolve("ready");
        Process launcher = new ProcessBuilder(javaCommand("launcher", directory.toString(), "blocked-input"))
                .redirectOutput(directory.resolve("launcher-out.txt").toFile())
                .redirectError(directory.resolve("launcher-err.txt").toFile())
                .start();
        try {
            await(() -> Files.exists(ready), Duration.ofSeconds(8));
            Thread.sleep(250); // Allow the console writer to fill the pipe before signalling the JVM.
            launcher.destroy();
            assertTrue(launcher.waitFor(8, TimeUnit.SECONDS), "A blocked stdin writer prevented shutdown");
            assertChildExited(ready);
        } finally {
            killFixture(launcher, ready);
        }
    }

    private static void assertChildExited(Path ready) throws Exception {
        long pid = Long.parseLong(Files.readString(ready));
        await(() -> ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true), Duration.ofSeconds(2));
    }

    private static void killFixture(Process launcher, Path ready) throws Exception {
        launcher.destroyForcibly();
        launcher.waitFor(2, TimeUnit.SECONDS);
        if (Files.exists(ready)) {
            ProcessHandle.of(Long.parseLong(Files.readString(ready))).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for the fixture process");
    }

    private static List<String> javaCommand(String... arguments) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        // Preserve the classpath when the child runs in a different working directory.
        String absoluteClasspath = Arrays.stream(classpath.split(java.io.File.pathSeparator))
                .map(entry -> Path.of(entry).toAbsolutePath().toString())
                .reduce((left, right) -> left + java.io.File.pathSeparator + right).orElseThrow();
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", absoluteClasspath, Fixture.class.getName()));
        command.addAll(List.of(arguments));
        return command;
    }

    private static final class TrackedInput extends ByteArrayInputStream {
        private boolean closed;

        TrackedInput(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    /** An actual JVM child keeps these tests independent of installed native tools and shells. */
    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "exercise" -> {
                    System.out.println("cwd=" + Path.of("").toRealPath());
                    System.out.println("arg=" + args[1]);
                    System.out.println("input=" + new BufferedReader(new InputStreamReader(
                            System.in, StandardCharsets.UTF_8)).readLine());
                    System.err.println("fixture-error");
                    System.exit(37);
                }
                case "launcher" -> {
                    Path directory = Path.of(args[1]);
                    InputStream input = args[2].equals("blocked-input") ? new InfiniteInput() : System.in;
                    System.exit(ProcessRunner.run(javaCommand(args[2], directory.toString()), directory, 1,
                            input, ProcessBuilder.Redirect.INHERIT, ProcessBuilder.Redirect.INHERIT));
                }
                case "await-stop" -> {
                    Path directory = Path.of(args[1]);
                    Files.writeString(directory.resolve("ready"), Long.toString(ProcessHandle.current().pid()));
                    BufferedReader console = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                    for (String line; (line = console.readLine()) != null;) {
                        if (line.equals("stop")) {
                            Files.writeString(directory.resolve("stopped"), "yes");
                            return;
                        }
                    }
                    System.exit(42);
                }
                case "blocked-input" -> {
                    Path directory = Path.of(args[1]);
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        try {
                            Thread.sleep(60_000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }));
                    Files.writeString(directory.resolve("ready"), Long.toString(ProcessHandle.current().pid()));
                    Thread.sleep(60_000);
                }
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }

    private static final class InfiniteInput extends InputStream {
        @Override
        public int read() {
            return 'x';
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            Arrays.fill(buffer, offset, offset + length, (byte) 'x');
            return length;
        }
    }
}

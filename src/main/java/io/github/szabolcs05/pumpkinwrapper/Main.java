// Copyright (c) 2026 Szabolcs05
// SPDX-License-Identifier: GPL-3.0-only
package io.github.szabolcs05.pumpkinwrapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        int status;
        try { status = run(args); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log("Interrupted.");
            status = 130;
        } catch (IOException | IllegalArgumentException e) {
            log("ERROR: " + e.getMessage());
            status = 1;
        }
        System.exit(status);
    }

    static int run(String[] args) throws IOException, InterruptedException {
        Options options = Options.parse(args);
        if (options.help()) {
            System.out.print("""
                    Pumpkin Java Wrapper - an independent launcher by Szabolcs05
                    Usage: java -Xms32M -Xmx128M -jar pumpkin-java-wrapper.jar [options] [-- server arguments]

                    --wrapper-help                 Show this help (also --help / -h).
                    --wrapper-version              Show wrapper version.
                    --wrapper-config <path>        Use a different properties file.
                    --wrapper-update               Explicitly refresh Pumpkin, then start it.
                    --wrapper-download-only        Install/verify Pumpkin without starting it.
                    --                             Pass all remaining arguments to Pumpkin verbatim.

                    Other arguments are forwarded; hosting-panel nogui / --nogui are ignored before --.
                    Configuration defaults to pumpkin-wrapper.properties in the current directory.
                    Normal starts verify and reuse the cached binary without accessing the network.
                    Java 17+ and permission to execute native child processes are required.
                    JVM memory flags apply to the launcher; Pumpkin uses native memory separately.

                    Copyright (c) 2026 Szabolcs05. GPL-3.0-only; NO WARRANTY.
                    License and source: https://github.com/Szabolcs05/pumpkin-java-wrapper
                    Pumpkin: https://github.com/Pumpkin-MC/Pumpkin
                    """);
            return 0;
        }
        if (options.version()) {
            String version = Main.class.getPackage().getImplementationVersion();
            System.out.println("Pumpkin Java Wrapper " + (version == null ? "development" : version));
            return 0;
        }
        Path directory = Path.of("").toAbsolutePath().normalize();
        Path cache = directory.resolve(".pumpkin-wrapper");
        Files.createDirectories(cache);
        try (FileChannel channel = FileChannel.open(cache.resolve("launcher.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Another wrapper is already running in this server directory.");
            Settings settings = Settings.load(directory.resolve(options.config()), System.getenv());
            Path binary = new Installer().resolve(settings, directory, cache, options.update());
            if (options.downloadOnly()) {
                log("Pumpkin is ready: " + binary);
                return 0;
            }
            List<String> command = new ArrayList<>();
            command.add(binary.toString());
            command.addAll(options.serverArgs());
            log("Starting Pumpkin in " + directory + ". Console commands are forwarded; use stop to save and exit.");
            try {
                return ProcessRunner.run(command, directory, settings.shutdownTimeoutSeconds());
            } catch (IOException e) {
                throw new IOException("Cannot run Pumpkin: " + e.getMessage()
                        + ". Check OS/CPU compatibility, native libraries, executable permissions, and your host's process policy.", e);
            }
        }
    }

    static void log(String message) { System.err.println("[Pumpkin Wrapper] " + message); }

    record Options(Path config, boolean update, boolean downloadOnly, boolean help, boolean version, List<String> serverArgs) {
        static Options parse(String[] args) {
            Path config = Path.of("pumpkin-wrapper.properties");
            boolean update = false, downloadOnly = false, help = false, version = false, passthrough = false;
            List<String> serverArgs = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (passthrough) { serverArgs.add(arg); continue; }
                switch (arg) {
                    case "--" -> passthrough = true;
                    case "--wrapper-help", "--help", "-h" -> help = true;
                    case "--wrapper-version" -> version = true;
                    case "--wrapper-update" -> update = true;
                    case "--wrapper-download-only" -> downloadOnly = true;
                    case "--wrapper-config" -> {
                        if (++i == args.length || args[i].isBlank() || args[i].startsWith("--")) {
                            throw new IllegalArgumentException("--wrapper-config requires a path.");
                        }
                        config = Path.of(args[i]);
                    }
                    case "nogui", "--nogui" -> { /* Common Java server panel arguments. */ }
                    default -> {
                        if (arg.startsWith("--wrapper-")) throw new IllegalArgumentException("Unknown wrapper option: " + arg);
                        serverArgs.add(arg);
                    }
                }
            }
            return new Options(config, update, downloadOnly, help, version, List.copyOf(serverArgs));
        }
    }
}

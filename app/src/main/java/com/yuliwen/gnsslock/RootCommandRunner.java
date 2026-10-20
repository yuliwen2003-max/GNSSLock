package com.yuliwen.gnsslock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Executes only the four fixed, root-owned GNSS LOCK commands. No shell input is accepted. */
final class RootCommandRunner {
    private static final String SCRIPT = "/data/adb/gpslock/gpslock-root.sh";
    private static final int OUTPUT_LIMIT = 64 * 1024;
    private volatile Process activeProcess;

    enum Action {
        STATUS("status"), ENABLE("enable"), DISABLE("disable"), APPLY("apply");
        final String argument;
        Action(String argument) { this.argument = argument; }
    }

    static final class Result {
        final int exitCode;
        final boolean timedOut;
        final String output;
        final boolean truncated;
        final String error;

        Result(int exitCode, boolean timedOut, String output, boolean truncated, String error) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.output = output;
            this.truncated = truncated;
            this.error = error;
        }
    }

    Result run(Action action) {
        Process process = null;
        OutputCollector collector = null;
        Thread reader = null;
        try {
            if (Thread.currentThread().isInterrupted()) {
                return new Result(-1, false, "", false, "操作已取消");
            }
            process = new ProcessBuilder("su", "-c", SCRIPT + " " + action.argument)
                    .redirectErrorStream(true).start();
            activeProcess = process;
            collector = new OutputCollector(process.getInputStream());
            reader = new Thread(collector, "GNSSLock-root-output");
            reader.setDaemon(true);
            reader.start();
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                terminate(process);
                closeOutput(process);
                reader.join(1000L);
                return new Result(-1, true, collector.snapshot(), collector.isTruncated(), "Root 命令等待超过 30 秒");
            }
            reader.join(1000L);
            if (reader.isAlive()) {
                closeOutput(process);
                reader.join(250L);
            }
            return new Result(process.exitValue(), false, collector.snapshot(), collector.isTruncated(), null);
        } catch (InterruptedException interrupted) {
            if (process != null) process.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Result(-1, false, collector == null ? "" : collector.snapshot(),
                    collector != null && collector.isTruncated(), "操作已取消");
        } catch (IOException | RuntimeException failure) {
            if (process != null) process.destroyForcibly();
            return new Result(-1, false, collector == null ? "" : collector.snapshot(),
                    collector != null && collector.isTruncated(), failure.getClass().getSimpleName()
                    + ": " + String.valueOf(failure.getMessage()));
        } finally {
            activeProcess = null;
            if (process != null) {
                closeOutput(process);
                if (process.isAlive()) process.destroyForcibly();
            }
        }
    }

    void cancel() {
        Process process = activeProcess;
        if (process != null) process.destroyForcibly();
    }

    private static void terminate(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(250L, TimeUnit.MILLISECONDS)) process.destroyForcibly();
    }

    private static void closeOutput(Process process) {
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
    }

    private static final class OutputCollector implements Runnable {
        private final InputStream stream;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private boolean truncated;

        OutputCollector(InputStream stream) { this.stream = stream; }

        @Override public void run() {
            byte[] chunk = new byte[4096];
            try {
                int count;
                while ((count = stream.read(chunk)) != -1) append(chunk, count);
            } catch (IOException ignored) {
                // A timeout or cancellation closes the pipe. The caller reports that state.
            }
        }

        private synchronized void append(byte[] chunk, int count) {
            int retained = Math.min(count, OUTPUT_LIMIT - output.size());
            if (retained > 0) output.write(chunk, 0, retained);
            if (retained < count) truncated = true;
            // Continue draining excess output so the child cannot block on a full pipe.
        }

        synchronized String snapshot() { return new String(output.toByteArray(), StandardCharsets.UTF_8); }
        synchronized boolean isTruncated() { return truncated; }
    }
}

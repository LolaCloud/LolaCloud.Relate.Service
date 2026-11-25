package com.miranda.luna.relate.infrastructure.docker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class DefaultDockerCommandRunner implements DockerCommandRunner {

    private static final Logger logger = LoggerFactory.getLogger(DefaultDockerCommandRunner.class);

    @Override
    public CommandResult run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException {
        logger.debug("Executing process: {}", String.join(" ", command));
        ProcessBuilder pb = new ProcessBuilder(command);
        Process process = pb.start();

        CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                logger.warn("Error reading process stdout: {}", e.getMessage());
                return "";
            }
        });

        CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            } catch (IOException e) {
                logger.warn("Error reading process stderr: {}", e.getMessage());
                return "";
            }
        });

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            stdoutFuture.cancel(true);
            stderrFuture.cancel(true);
            String timeoutMsg = "Command timed out after " + timeoutSeconds + " seconds: " + String.join(" ", command);
            logger.warn(timeoutMsg);
            return new CommandResult(-1, "", timeoutMsg);
        }

        int exitCode = process.exitValue();
        String stdout = "";
        String stderr = "";
        try {
            stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Error waiting for stdout drain: {}", e.getMessage());
        }
        try {
            stderr = stderrFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warn("Error waiting for stderr drain: {}", e.getMessage());
        }

        if (exitCode != 0 && (stderr.contains("permission denied") || stderr.contains("dial unix /var/run/docker.sock"))) {
            logger.info("Direct docker command failed with socket permission denied. Retrying via 'sg docker'...");
            try {
                List<String> sgCmd = new java.util.ArrayList<>();
                sgCmd.add("sg");
                sgCmd.add("docker");
                sgCmd.add("-c");
                sgCmd.add(String.join(" ", command));

                Process sgProcess = new ProcessBuilder(sgCmd).start();
                CompletableFuture<String> sgStdoutFuture = CompletableFuture.supplyAsync(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(sgProcess.getInputStream(), StandardCharsets.UTF_8))) {
                        return reader.lines().collect(Collectors.joining("\n"));
                    } catch (IOException e) {
                        return "";
                    }
                });
                CompletableFuture<String> sgStderrFuture = CompletableFuture.supplyAsync(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(sgProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                        return reader.lines().collect(Collectors.joining("\n"));
                    } catch (IOException e) {
                        return "";
                    }
                });

                boolean sgFinished = sgProcess.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!sgFinished) {
                    sgProcess.destroyForcibly();
                    return new CommandResult(-1, "", "Timeout via sg docker");
                }
                return new CommandResult(sgProcess.exitValue(), sgStdoutFuture.get(5, TimeUnit.SECONDS), sgStderrFuture.get(5, TimeUnit.SECONDS));
            } catch (Exception e) {
                logger.warn("Failed retrying via sg docker: {}", e.getMessage());
            }
        }

        return new CommandResult(exitCode, stdout, stderr);
    }
}

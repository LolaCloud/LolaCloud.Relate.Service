package com.miranda.luna.relate.infrastructure.docker;

import java.io.IOException;
import java.util.List;

public interface DockerCommandRunner {

    CommandResult run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException;
}

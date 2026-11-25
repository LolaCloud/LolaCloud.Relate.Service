package com.miranda.luna.relate.infrastructure.docker;

import com.miranda.luna.relate.domain.exception.InvalidOperationException;

public class DockerProvisionerException extends InvalidOperationException {

    public DockerProvisionerException(String message) {
        super(message);
    }

    public DockerProvisionerException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}

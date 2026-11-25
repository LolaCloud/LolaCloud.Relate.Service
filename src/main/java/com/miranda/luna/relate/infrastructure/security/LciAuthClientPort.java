package com.miranda.luna.relate.infrastructure.security;

import com.miranda.luna.relate.domain.model.AuthenticatedOperator;

import java.util.Optional;

public interface LciAuthClientPort {
    Optional<AuthenticatedOperator> introspect(String token);
}

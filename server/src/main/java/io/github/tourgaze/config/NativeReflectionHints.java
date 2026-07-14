/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

import io.github.tourgaze.dto.RouteCandidate;

/**
 * GraalVM native-image reflection hints.
 *
 * <p>
 * {@link io.github.tourgaze.repository.ActivityRepository#findRouteCandidates}
 * uses a JPQL constructor expression ({@code select new ...RouteCandidate(...)}),
 * so Hibernate instantiates the DTO reflectively at runtime. The native-image
 * static analysis can't see that call, drops the constructor, and the query
 * fails at boot with {@code SemanticException: Missing constructor for type
 * 'RouteCandidate'}. Registering the DTO keeps its constructor in the image.
 *
 * <p>
 * Add any future {@code select new <Dto>(...)} target here.
 */
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding(RouteCandidate.class)
public class NativeReflectionHints {
}

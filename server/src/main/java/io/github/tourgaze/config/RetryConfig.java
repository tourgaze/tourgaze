/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Enables {@code @Retryable} so transient upstream failures (e.g. a public
 * map-tile API timing out) can be retried declaratively. See
 * {@code TileFetcher}.
 */
@Configuration
@EnableRetry
public class RetryConfig {
}

/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
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

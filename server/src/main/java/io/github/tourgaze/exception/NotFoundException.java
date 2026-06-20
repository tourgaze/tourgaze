/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.exception;

/**
 * Thrown by the service/controller layer when a requested entity does not
 * exist.
 * Mapped to HTTP 404 by
 * {@link io.github.tourgaze.config.GlobalExceptionHandler}.
 */
public class NotFoundException extends RuntimeException {
	public NotFoundException(String message) {
		super(message);
	}
}

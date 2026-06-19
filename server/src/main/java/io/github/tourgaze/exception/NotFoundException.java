/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
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

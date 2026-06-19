/*
 * Copyright (c) 2026 Tourgaze
 * This program is dual-licensed under:
 * GNU Affero General Public License (AGPL v3) - Open Source, Copyleft.
 * Commercial License - Proprietary, Closed Source.
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import io.github.tourgaze.dto.ApiErrorResponse;
import io.github.tourgaze.exception.NotFoundException;

/**
 * Turns exceptions into a uniform {@link ApiErrorResponse} with the right HTTP
 * status, instead of leaking raw 500s / stack traces to the client.
 *
 * The two that matter most for data soundness:
 * - {@link DataIntegrityViolationException} (FK / unique / not-null breach) →
 * 409
 * - {@link OptimisticLockingFailureException} (concurrent write, @Version) →
 * 409
 * Without this, a unique-username clash, a duplicate sibling tag, or two
 * writers
 * racing on the same activity all surfaced as opaque 500s.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/** Requested entity not found. */
	@ExceptionHandler({ NotFoundException.class, NoResourceFoundException.class })
	public ResponseEntity<ApiErrorResponse> handleNotFound(Exception ex, HttpServletRequest req) {
		return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), req);
	}

	/** DB constraint breach — FK, unique, or not-null. */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiErrorResponse> handleConstraint(DataIntegrityViolationException ex,
			HttpServletRequest req) {
		log.warn("Constraint violation on {}: {}", req.getRequestURI(),
				ex.getMostSpecificCause().getMessage());
		return build(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION",
				"The request conflicts with existing data.", req);
	}

	/** Two writers raced on the same row (optimistic locking via @Version). */
	@ExceptionHandler(OptimisticLockingFailureException.class)
	public ResponseEntity<ApiErrorResponse> handleLock(OptimisticLockingFailureException ex,
			HttpServletRequest req) {
		return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
				"This record was modified by another operation — reload and retry.", req);
	}

	/** Bean-validation failure on a @Valid body. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex,
			HttpServletRequest req) {
		String detail = ex.getBindingResult().getFieldErrors().stream()
				.findFirst()
				.map(f -> f.getField() + " " + f.getDefaultMessage())
				.orElse("Validation failed");
		return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail, req);
	}

	/** Malformed / unparseable request body. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiErrorResponse> handleMalformed(HttpMessageNotReadableException ex,
			HttpServletRequest req) {
		return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Invalid request body.", req);
	}

	/** Controllers that throw ResponseStatusException keep their chosen status. */
	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException ex,
			HttpServletRequest req) {
		HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
		return build(status, status.name(),
				ex.getReason() != null ? ex.getReason() : status.getReasonPhrase(), req);
	}

	/**
	 * Client closed the connection mid-response — e.g. the browser cancels
	 * in-flight tile downloads when the map pans away. The socket is gone, so
	 * there's nothing to send: return void (no body) rather than letting it reach
	 * {@link #handleGeneral}, which would log a stack trace and then fail again
	 * trying to serialize a JSON error into the already-committed (image/png)
	 * response. Logged at debug — it's normal, not a server fault.
	 */
	@ExceptionHandler({ ClientAbortException.class, AsyncRequestNotUsableException.class })
	public void handleClientAbort(Exception ex) {
		log.debug("Client aborted request: {}", ex.getMessage());
	}

	/**
	 * Anything unanticipated → 500, with a server-side stack trace for diagnosis.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiErrorResponse> handleGeneral(Exception ex, HttpServletRequest req) {
		log.error("Unhandled exception on {}", req.getRequestURI(), ex);
		return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Server error.", req);
	}

	private ResponseEntity<ApiErrorResponse> build(HttpStatus status, String code,
			String message, HttpServletRequest req) {
		return ResponseEntity.status(status)
				.body(ApiErrorResponse.of(status.value(), code, message, req.getRequestURI()));
	}
}

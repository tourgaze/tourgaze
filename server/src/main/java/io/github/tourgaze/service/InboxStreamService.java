/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.github.tourgaze.enums.InboxStreamEvent;

/**
 * Server-Sent-Events hub for the inbox — the push side of "background inbox"
 * (matros message-bus equivalent). The frontend opens one long-lived stream and
 * refetches whenever an {@code inbox-changed} event arrives, instead of
 * polling.
 * A heartbeat keeps the connection alive through idle/proxy timeouts; dead
 * emitters are pruned on the next send.
 */
@Service
public class InboxStreamService {

	private static final Logger log = LoggerFactory.getLogger(InboxStreamService.class);
	private static final long STREAM_TIMEOUT_MS = 60 * 60 * 1000L; // 1h; client auto-reconnects

	private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

	/**
	 * Register a new subscriber. The caller returns this from a controller method.
	 */
	public SseEmitter subscribe() {
		SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
		emitters.add(emitter);
		emitter.onCompletion(() -> emitters.remove(emitter));
		emitter.onTimeout(() -> emitters.remove(emitter));
		emitter.onError(e -> emitters.remove(emitter));
		try {
			emitter.send(SseEmitter.event().name(InboxStreamEvent.CONNECTED.wire()).data("ok"));
		} catch (IOException e) {
			emitters.remove(emitter);
		}
		return emitter;
	}

	/** Tell every subscriber the inbox changed → they refetch GET /api/inbox. */
	public void changed() {
		send(SseEmitter.event().name(InboxStreamEvent.INBOX_CHANGED.wire()).data("1"));
	}

	/** Keep idle connections alive (Tomcat async / reverse-proxy timeouts). */
	@Scheduled(fixedRate = 25_000)
	public void heartbeat() {
		if (!emitters.isEmpty())
			send(SseEmitter.event().comment("keepalive"));
	}

	private void send(SseEmitter.SseEventBuilder event) {
		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(event);
			} catch (Throwable t) {
				// A dead client (browser closed/navigated) makes send() throw. Drop it
				// and keep going — this must NEVER propagate, or it would kill the
				// background warm that calls changed(). Throwable, not Exception, so
				// even an async write Error can't escape.
				emitters.remove(emitter);
				try {
					emitter.complete();
				} catch (Throwable ignored) {
					/* already broken */ }
			}
		}
	}
}

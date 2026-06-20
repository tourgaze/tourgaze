/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Fetches map tiles from the upstream provider. Split out of TileController so
 * the network call can be wrapped with {@link Retryable}: public tile APIs
 * (OSM,
 * Carto, ESRI, …) occasionally time out or drop a connection, and a single miss
 * shouldn't fail the tile. Lives in its own bean because {@code @Retryable}
 * works
 * through a Spring proxy — a self-invocation inside the controller wouldn't
 * retry.
 */
@Component
public class TileFetcher {

	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	/**
	 * GET the tile bytes. Retries transient network failures — connect/read
	 * timeouts surface as {@link IOException} subtypes, so retrying on IOException
	 * covers them. A non-200 response returns normally (the caller decides); only
	 * thrown failures are retried.
	 */
	@Retryable(retryFor = IOException.class, maxAttempts = 3, backoff = @Backoff(delay = 300, multiplier = 2))
	public HttpResponse<byte[]> fetch(String url) throws IOException, InterruptedException {
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("User-Agent", "TourGaze/1.0 (tile cache)")
				.timeout(Duration.ofSeconds(15))
				.build();
		return httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
	}
}

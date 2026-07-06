/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.tourgaze.dto.TileProviderDto;
import io.github.tourgaze.entity.Setting;
import io.github.tourgaze.repository.SettingRepository;
import io.github.tourgaze.service.TileFetcher;
import io.github.tourgaze.service.TileProviderRegistry;
import io.github.tourgaze.store.StorageService;

/**
 * Proxies tile requests through the backend and caches them locally.
 *
 * GET /api/tiles/{z}/{x}/{y}.png?provider=<id>
 *
 * The provider ID is used as the cache subdirectory, so switching providers
 * never serves stale tiles from a previous provider.
 *
 * Tile URL template is read from DB setting "map.tile.url.{provider}";
 * falls back to "map.tile.url" if no provider-specific key exists.
 * Default: https://tile.openstreetmap.org/{z}/{x}/{y}.png
 */
@RestController
public class TileController {

	private static final Logger log = LoggerFactory.getLogger(TileController.class);
	private static final String[] SUBDOMAINS = { "a", "b", "c" };
	private static final String DEFAULT_TILE_URL = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";

	/**
	 * Hard-coded fallback URLs per provider — overridable via DB settings
	 * (`map.tile.url.{providerid}`). Synchronized with
	 * {@link io.github.tourgaze.service.TileProviderRegistry}: every raster
	 * provider listed there must have a corresponding upstream URL here, or
	 * the proxy will fall back to the generic DEFAULT_TILE_URL.
	 */
	private static final java.util.Map<String, String> PROVIDER_DEFAULTS = java.util.Map.of(
			"osm", "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
			"carto-light", "https://basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png",
			"carto-dark", "https://basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
			"esri-topo",
			"https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/{z}/{y}/{x}",
			"esri-imagery",
			"https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
			"terrain", "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png");

	private final StorageService storage;
	private final SettingRepository settingRepo;
	private final TileProviderRegistry providerRegistry;
	private final io.github.tourgaze.repository.MapProviderRepository mapProviderRepo;
	private final TileFetcher tileFetcher;

	public TileController(StorageService storage, SettingRepository settingRepo,
			TileProviderRegistry providerRegistry,
			io.github.tourgaze.repository.MapProviderRepository mapProviderRepo,
			TileFetcher tileFetcher) {
		this.storage = storage;
		this.settingRepo = settingRepo;
		this.providerRegistry = providerRegistry;
		this.mapProviderRepo = mapProviderRepo;
		this.tileFetcher = tileFetcher;
	}

	/** Catalog of available basemap providers, surfaced to the frontend picker. */
	@GetMapping("/api/tile-providers")
	public java.util.List<TileProviderDto> providers() {
		return providerRegistry.all();
	}

	@GetMapping("/api/tiles/{z}/{x}/{y}.png")
	public ResponseEntity<byte[]> tile(@PathVariable("z") int z,
			@PathVariable("x") int x,
			@PathVariable("y") int y,
			@RequestParam(value = "providerid", defaultValue = "osm") String provider) {
		// Validate provider id — prevents path traversal
		try {
			StorageService.validateProvider(provider);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().build();
		}

		// Validate tile coordinates
		if (z < 0 || z > 19)
			return ResponseEntity.badRequest().build();
		int maxCoord = 1 << z;
		if (x < 0 || x >= maxCoord || y < 0 || y >= maxCoord) {
			return ResponseEntity.badRequest().build();
		}

		// Serve from local cache if available (keyed by provider). If the
		// read fails (corrupt cache file, FS error), fall through to the
		// upstream fetch path below — same outcome as a cold cache.
		Path cached = storage.tileFile(provider, z, x, y);
		if (Files.exists(cached)) {
			try {
				return tileResponse(Files.readAllBytes(cached));
			} catch (IOException e) {
				log.debug("Cache read failed for {}/{}/{}/{}: {}", provider, z, x, y, e.getMessage());
			}
		}

		// Fetch from upstream — prefer provider-specific setting key, then provider
		// default, then generic default
		String tileUrl = settingRepo.findById("map.tile.url." + provider)
				.map(Setting::getValue)
				.or(() -> java.util.Optional.ofNullable(PROVIDER_DEFAULTS.get(provider)))
				// User-defined raster providers carry their upstream XYZ url here.
				.or(() -> mapProviderRepo.findById(provider)
						.map(io.github.tourgaze.entity.MapProvider::getUrlTemplate))
				.or(() -> settingRepo.findById("map.tile.url").map(Setting::getValue))
				.orElse(DEFAULT_TILE_URL);

		String sub = SUBDOMAINS[(x + y) % SUBDOMAINS.length];
		String url = tileUrl
				.replace("{s}", sub)
				.replace("{z}", String.valueOf(z))
				.replace("{x}", String.valueOf(x))
				.replace("{y}", String.valueOf(y));

		try {
			// Retried on transient network failures inside TileFetcher.
			HttpResponse<byte[]> resp = tileFetcher.fetch(url);
			if (resp.statusCode() != 200) {
				return ResponseEntity.status(resp.statusCode()).build();
			}

			byte[] tileData = resp.body();
			// Only cache genuine images. Tile servers/CDNs/captive portals can
			// answer 200 with an HTML error page or empty body; caching that as a
			// .png would poison the immutable cache (served for a year), so reject
			// it as a bad gateway instead of persisting garbage.
			if (!io.github.tourgaze.util.ImageSniff.isImage(tileData)) {
				log.warn("Upstream tile was not an image z={} x={} y={} ({} bytes) — not caching",
						z, x, y, tileData == null ? 0 : tileData.length);
				return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
			}
			Files.createDirectories(cached.getParent());
			// Write-then-rename so a concurrent request for the same tile can never
			// read a half-written file — the browser would cache the corrupt tile
			// for a year (`immutable` below). Unique temp name: two concurrent
			// misses must not truncate each other's temp file mid-copy either.
			Path tmp = cached.resolveSibling(cached.getFileName() + "." + Thread.currentThread().threadId() + ".tmp");
			Files.write(tmp, tileData);
			try {
				Files.move(tmp, cached, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
						java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				// Lost the race to another writer — theirs is identical; clean up.
				Files.deleteIfExists(tmp);
			}

			return tileResponse(tileData);
		} catch (Exception e) {
			log.warn("Tile fetch failed z={} x={} y={}: {}", z, x, y, e.getMessage());
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		}
	}

	private ResponseEntity<byte[]> tileResponse(byte[] data) {
		// OSM / terrain tiles at fixed (provider, z, x, y) coords are effectively
		// immutable — the upstream content doesn't change at that address. The
		// `immutable` directive lets the browser skip the conditional GET round
		// trip entirely until the entry is evicted. 1 year is the conventional
		// upper bound for `immutable` per RFC 8246.
		return ResponseEntity.ok()
				.contentType(MediaType.IMAGE_PNG)
				.header("Cache-Control", "public, max-age=31536000, immutable")
				.body(data);
	}

}

/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.util.concurrent.TimeUnit;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * In-process Caffeine cache for hot reads.
 *
 * settings — read on most page renders. 10-min TTL.
 * diskUsage — admin/disk walks the FS. 30-sec TTL.
 * geocode — Nominatim reverse-geo for PredictionService. 7-day TTL since
 * place names don't move; bounded by lat/lon-rounded key (~100m).
 */
@Configuration
@EnableCaching
public class CacheConfig {

	@Bean
	public CacheManager cacheManager() {
		CaffeineCacheManager mgr = new CaffeineCacheManager();
		mgr.registerCustomCache("settings",
				Caffeine.newBuilder().maximumSize(64).expireAfterWrite(10, TimeUnit.MINUTES).build());
		mgr.registerCustomCache("diskUsage",
				Caffeine.newBuilder().maximumSize(1).expireAfterWrite(30, TimeUnit.SECONDS).build());
		mgr.registerCustomCache("geocode",
				Caffeine.newBuilder().maximumSize(2000).expireAfterWrite(7, TimeUnit.DAYS).build());
		return mgr;
	}
}

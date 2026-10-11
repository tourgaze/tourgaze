/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
	// Cross-origin callers the operator explicitly trusts (tourgaze.allowed-origins /
	// TOURGAZE_ALLOWED_ORIGINS); empty by default — the SPA is same-origin, and so
	// is `vite dev` through its proxy. Every other origin is already refused by
	// ForeignSiteFilter; this mapping only adds the CORS headers the trusted ones need.
	private final AllowedOrigins allowedOrigins;

	public WebConfig(AllowedOrigins allowedOrigins) {
		this.allowedOrigins = allowedOrigins;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOrigins(allowedOrigins.origins().toArray(String[]::new))
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
				.allowedHeaders("*");
	}
}

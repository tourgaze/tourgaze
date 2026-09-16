/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
	// Defaults to the Vite dev-server origins; override via tourgaze.allowed-origins
	// (TOURGAZE_ALLOWED_ORIGINS) for any other deployment. Needed even when a
	// reverse proxy serves the frontend and API on the same public origin: Spring
	// only knows its own localhost:8085 here, so it can't infer that the proxy's
	// domain counts as "the same origin" from its perspective.
	@Value("${tourgaze.allowed-origins}")
	private String[] allowedOrigins;

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/api/**")
				.allowedOrigins(allowedOrigins)
				.allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
				.allowedHeaders("*");
	}
}

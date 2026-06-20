/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA deep-link fallback for the single-container build. The Vue app uses HTML5
 * history routing, so a direct hit or refresh on a client route (e.g.
 * {@code /inbox}, {@code /dashboard}) must return the SPA shell ({@code
 * index.html}) — otherwise Spring 404s because there's no static file there.
 * API
 * ({@code /api/**}), actuator and hashed assets (paths with a dot) are
 * untouched;
 * the client router takes over once the shell loads.
 *
 * Keep this list in sync with the routes in {@code frontend/src/main.ts}.
 */
@Controller
public class SpaForwardController {

	@GetMapping({
			"/tours", "/tour/**", "/compare/**", "/inbox",
			"/dashboard", "/markers", "/settings", "/about", "/setup",
	})
	public String forwardToSpa() {
		return "forward:/index.html";
	}
}

/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Guards the wiring, not the logic (that's {@link ForeignSiteFilterTest}): the
 * protection must actually sit in front of the running app. TourGaze has no
 * login, so if it is ever removed or unregistered, every website the user
 * visits can read and change their library again — and this test fails.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ForeignSiteProtectionTest {

	@Autowired
	private MockMvc mvc;

	@Test
	void ownRequestsWork() throws Exception {
		mvc.perform(get("/api/sports")).andExpect(status().isOk());
	}

	@Test
	void foreignWebsiteCannotWrite() throws Exception {
		mvc.perform(post("/api/users")
				.header(HttpHeaders.ORIGIN, "https://evil.example")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void dnsRebindingCannotRead() throws Exception {
		mvc.perform(get("/api/sports").header(HttpHeaders.HOST, "evil.example:8085"))
				.andExpect(status().isForbidden());
	}
}

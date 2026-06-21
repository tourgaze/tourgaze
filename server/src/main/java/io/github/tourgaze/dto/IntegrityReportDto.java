/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.util.List;

/**
 * Result of the DB ↔ store filesystem integrity check (matros-style).
 *
 * @param totalActivities rows checked
 * @param missing         activities whose stored ride file is gone
 * @param corrupt         activities whose file content no longer matches the
 *                        recorded {@code sourceHash} (bit-rot / sync damage), or
 *                        couldn't be read/decrypted
 * @param orphanFolders   {@code store/<id>/} folders with no matching activity
 */
public record IntegrityReportDto(
        int totalActivities,
        List<Ref> missing,
        List<Ref> corrupt,
        List<String> orphanFolders) {

    /** A referenced activity (id + display name). */
    public record Ref(String id, String name) {}
}

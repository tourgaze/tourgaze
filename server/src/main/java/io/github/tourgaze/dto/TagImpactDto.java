/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.dto;

import java.util.List;

/**
 * What deleting a tag would take down with it — shown to the user as a
 * confirmation before the (cascading) delete. Deleting a tag cascades to its
 * entire child subtree, removes every activity↔tag link for the tag and its
 * descendants, and clears the grouping of any filter preset that grouped by one
 * of them.
 *
 * @param tagId            the tag whose impact this describes
 * @param tagName          its display name
 * @param descendantTags   number of child tags (recursive) that will also be deleted
 * @param descendantNames  those child tags' names (capped for display)
 * @param activities       distinct activities that will lose a tag link
 * @param presets          filter presets whose "group by tag" will be cleared
 */
public record TagImpactDto(
        String tagId,
        String tagName,
        int descendantTags,
        List<String> descendantNames,
        long activities,
        long presets) {}

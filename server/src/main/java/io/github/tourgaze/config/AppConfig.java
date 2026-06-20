/*
 * Copyright (c) 2026 Tourgaze
 * Licensed under the GNU Affero General Public License v3.0 (AGPL-3.0).
 * See the LICENSE file for full details.
 */
package io.github.tourgaze.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds to "tourgaze.*" properties.
 * Override data directory via TOURGAZE_DATA_DIR env variable or
 * tourgaze.data-dir property.
 */
@Component
@ConfigurationProperties("tourgaze")
public class AppConfig {

	private String dataDir = System.getProperty("user.home") + "/.tourgaze";

	/**
	 * Where the precious, cloud-syncable data lives — {@code store/} (ride files,
	 * photos, metadata sidecars) and {@code db-backup/} (H2 snapshots). Point it at
	 * a cloud-synced folder (Dropbox, Google Drive, …) to back up the library; the
	 * DB + caches stay local under {@link #dataDir} and rebuild from the sidecars.
	 * Null/blank → defaults to {@code dataDir/repository}, so the precious data
	 * lives in one tidy folder separate from the regenerable workspace.
	 */
	private String repositoryDir;

	private Inbox inbox = new Inbox();

	public String getDataDir() {
		return dataDir;
	}

	public void setDataDir(String dataDir) {
		this.dataDir = dataDir;
	}

	public String getRepositoryDir() {
		return (repositoryDir == null || repositoryDir.isBlank()) ? dataDir + "/repository" : repositoryDir;
	}

	public void setRepositoryDir(String repositoryDir) {
		this.repositoryDir = repositoryDir;
	}

	public Inbox getInbox() {
		return inbox;
	}

	public void setInbox(Inbox inbox) {
		this.inbox = inbox;
	}

	/**
	 * Deployment-time inbox config ({@code tourgaze.inbox.sources}). These are
	 * only a default: the {@code inbox.sources} DB setting (edited in Settings →
	 * Inbox) takes precedence when present, so a UI change always wins over what
	 * a container/yaml ships with.
	 */
	public static class Inbox {
		private List<Source> sources = new ArrayList<>();

		public List<Source> getSources() {
			return sources;
		}

		public void setSources(List<Source> sources) {
			this.sources = sources;
		}

		public static class Source {
			private String label;
			private String path;
			/**
			 * Keep the original in the source folder (copy). When false the scanner
			 * moves it out (deletes from the device after a successful copy). Default
			 * true — never touch the user's device files unless they opt in.
			 */
			private boolean keepOriginal = true;

			public String getLabel() {
				return label;
			}

			public void setLabel(String label) {
				this.label = label;
			}

			public String getPath() {
				return path;
			}

			public void setPath(String path) {
				this.path = path;
			}

			public boolean isKeepOriginal() {
				return keepOriginal;
			}

			public void setKeepOriginal(boolean keepOriginal) {
				this.keepOriginal = keepOriginal;
			}
		}
	}
}

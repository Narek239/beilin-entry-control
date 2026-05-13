package us.beiyue.beilinentryportability.common;

import java.nio.file.Path;

public final class ExportArtifact {
	public final Path path;
	public final long bytes;
	public final String sha256;

	public ExportArtifact(Path path, long bytes, String sha256) {
		this.path = path;
		this.bytes = bytes;
		this.sha256 = sha256;
	}
}

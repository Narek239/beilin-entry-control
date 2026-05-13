package us.beiyue.beilinentryportability.common;

import java.util.List;

public final class ExportBundle {
	public final ExportManifest manifest;
	public final List<ComponentExport> components;

	public ExportBundle(ExportManifest manifest, List<ComponentExport> components) {
		this.manifest = manifest;
		this.components = List.copyOf(components);
	}
}

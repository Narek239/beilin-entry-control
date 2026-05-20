package us.beiyue.beilindataportability.common;

import java.util.List;

public final class ComponentExport {
	public final ComponentSummary summary;
	public final List<BlockRecord> blocks;

	public ComponentExport(ComponentSummary summary, List<BlockRecord> blocks) {
		this.summary = summary;
		this.blocks = List.copyOf(blocks);
	}
}

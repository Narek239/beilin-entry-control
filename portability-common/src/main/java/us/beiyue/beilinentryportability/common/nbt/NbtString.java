package us.beiyue.beilinentryportability.common.nbt;

import java.io.DataOutput;
import java.io.IOException;

public final class NbtString implements NbtValue {
	private final String value;

	public NbtString(String value) {
		this.value = value != null ? value : "";
	}

	@Override
	public byte type() {
		return STRING;
	}

	@Override
	public void writePayload(DataOutput out) throws IOException {
		out.writeUTF(value);
	}
}

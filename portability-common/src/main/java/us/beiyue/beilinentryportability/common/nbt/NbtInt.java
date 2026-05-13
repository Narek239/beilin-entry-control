package us.beiyue.beilinentryportability.common.nbt;

import java.io.DataOutput;
import java.io.IOException;

public final class NbtInt implements NbtValue {
	private final int value;

	public NbtInt(int value) {
		this.value = value;
	}

	@Override
	public byte type() {
		return INT;
	}

	@Override
	public void writePayload(DataOutput out) throws IOException {
		out.writeInt(value);
	}
}

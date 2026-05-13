package us.beiyue.beilinentryportability.common.nbt;

import java.io.DataOutput;
import java.io.IOException;

public final class NbtLong implements NbtValue {
	private final long value;

	public NbtLong(long value) {
		this.value = value;
	}

	@Override
	public byte type() {
		return LONG;
	}

	@Override
	public void writePayload(DataOutput out) throws IOException {
		out.writeLong(value);
	}
}

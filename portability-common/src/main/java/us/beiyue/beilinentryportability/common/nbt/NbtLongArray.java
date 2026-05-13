package us.beiyue.beilinentryportability.common.nbt;

import java.io.DataOutput;
import java.io.IOException;

public final class NbtLongArray implements NbtValue {
	private final long[] value;

	public NbtLongArray(long[] value) {
		this.value = value != null ? value : new long[0];
	}

	@Override
	public byte type() {
		return LONG_ARRAY;
	}

	@Override
	public void writePayload(DataOutput out) throws IOException {
		out.writeInt(value.length);
		for (long v : value) out.writeLong(v);
	}
}

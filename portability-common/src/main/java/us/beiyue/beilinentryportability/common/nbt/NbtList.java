package us.beiyue.beilinentryportability.common.nbt;

import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class NbtList implements NbtValue {
	private final byte elementType;
	private final List<NbtValue> values = new ArrayList<>();

	public NbtList(byte elementType) {
		this.elementType = elementType;
	}

	public NbtList add(NbtValue value) {
		if (value != null) {
			if (value.type() != elementType) {
				throw new IllegalArgumentException("NBT list element type mismatch");
			}
			values.add(value);
		}
		return this;
	}

	@Override
	public byte type() {
		return LIST;
	}

	@Override
	public void writePayload(DataOutput out) throws IOException {
		out.writeByte(elementType);
		out.writeInt(values.size());
		for (NbtValue value : values) {
			value.writePayload(out);
		}
	}
}

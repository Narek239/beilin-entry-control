package us.beiyue.beilinentryportability.common.nbt;

import java.io.DataOutput;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class NbtCompound implements NbtValue {
	private final Map<String, NbtValue> values = new LinkedHashMap<>();

	public NbtCompound put(String name, NbtValue value) {
		if (name == null || value == null) return this;
		values.put(name, value);
		return this;
	}

	public NbtCompound putString(String name, String value) {
		return put(name, new NbtString(value));
	}

	public NbtCompound putInt(String name, int value) {
		return put(name, new NbtInt(value));
	}

	public NbtCompound putLong(String name, long value) {
		return put(name, new NbtLong(value));
	}

	@Override
	public byte type() {
		return COMPOUND;
	}

	@Override
	public void writePayload(DataOutput out) throws IOException {
		for (Map.Entry<String, NbtValue> entry : values.entrySet()) {
			NbtValue value = entry.getValue();
			out.writeByte(value.type());
			out.writeUTF(entry.getKey());
			value.writePayload(out);
		}
		out.writeByte(END);
	}
}

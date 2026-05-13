package us.beiyue.beilinentryportability.common.nbt;

import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

public final class NbtIo {
	private NbtIo() {
	}

	public static void writeCompressed(NbtCompound root, String rootName, OutputStream output) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new NonClosingOutputStream(output)))) {
			out.writeByte(NbtValue.COMPOUND);
			out.writeUTF(rootName != null ? rootName : "");
			root.writePayload(out);
		}
	}

	private static final class NonClosingOutputStream extends FilterOutputStream {
		private NonClosingOutputStream(OutputStream out) {
			super(out);
		}

		@Override
		public void close() throws IOException {
			flush();
		}
	}
}

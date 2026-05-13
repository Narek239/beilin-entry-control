package us.beiyue.beilinentryportability.common;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class LitematicWriterRoundTripTest {
	public static void main(String[] args) throws Exception {
		ComponentExport component = component();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		LitematicWriter.write(component, "test", out);
		ParsedLitematic parsed = parseLitematic(out.toByteArray());
		assertEquals(35, parsed.nonAirBlocks, "direct litematic non-air count");
		assertEquals(0, parsed.positionX, "region local x");
		assertEquals(0, parsed.positionY, "region local y");
		assertEquals(0, parsed.positionZ, "region local z");

		Path dir = Files.createTempDirectory("beilin-entry-litematic-roundtrip");
		try {
			ExportManifest manifest = new ExportManifest(12L, "Alice", "2026-05-13T00:00:00Z", 35, List.of(component.summary));
			ExportBundle bundle = new ExportBundle(manifest, List.of(component));
			ExportArtifact artifact = ExportPackageWriter.writeZip(dir, new ExportJob(12L, "Alice", null, null), bundle);
			byte[] litematic = readFirstLitematicFromZip(artifact.path);
			ParsedLitematic parsedZipEntry = parseLitematic(litematic);
			assertEquals(35, parsedZipEntry.nonAirBlocks, "zip litematic non-air count");
		} finally {
			deleteTree(dir);
		}
	}

	private static ComponentExport component() {
		List<BlockRecord> blocks = new ArrayList<>();
		for (int i = 0; i < 35; i++) {
			String state = switch (i % 3) {
				case 0 -> "minecraft:stone";
				case 1 -> "minecraft:oak_planks";
					default -> "minecraft:blue_stained_glass";
			};
			blocks.add(new BlockRecord(
				"minecraft:overworld",
				100 + i,
				64,
				-30,
				state
			));
		}
		ComponentSummary summary = new ComponentSummary(
			1,
			10L,
			"minecraft:overworld",
			100, 64, -30,
			134, 64, -30,
			35,
			35,
			10000,
			"2026-05-13T00:00:00Z",
			1,
			null,
			"001_test.litematic"
		);
		return new ComponentExport(summary, blocks);
	}

	private static byte[] readFirstLitematicFromZip(Path zipPath) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipPath))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (!entry.isDirectory() && entry.getName().endsWith(".litematic")) {
					return zip.readAllBytes();
				}
			}
		}
		throw new AssertionError("zip contained no litematic entry");
	}

	@SuppressWarnings("unchecked")
	private static ParsedLitematic parseLitematic(byte[] bytes) throws IOException {
		Object root;
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
			byte type = in.readByte();
			if (type != 10) throw new AssertionError("root was not a compound");
			in.readUTF();
			root = readPayload(in, type);
		}
		Map<String, Object> rootMap = (Map<String, Object>) root;
		Map<String, Object> regions = (Map<String, Object>) rootMap.get("Regions");
		Map<String, Object> region = (Map<String, Object>) regions.get("Region 0");
		Map<String, Object> position = (Map<String, Object>) region.get("Position");
		Map<String, Object> size = (Map<String, Object>) region.get("Size");
		List<Object> palette = (List<Object>) region.get("BlockStatePalette");
		long[] blockStates = (long[]) region.get("BlockStates");
		int volume = ((Integer) size.get("x")) * ((Integer) size.get("y")) * ((Integer) size.get("z"));
		int bitsPerEntry = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
		int nonAir = 0;
		long mask = (1L << bitsPerEntry) - 1L;
		for (int index = 0; index < volume; index++) {
			int bitIndex = index * bitsPerEntry;
			int longIndex = bitIndex >>> 6;
			int bitOffset = bitIndex & 63;
			long value = (blockStates[longIndex] >>> bitOffset) & mask;
			int spill = bitOffset + bitsPerEntry - 64;
			if (spill > 0 && longIndex + 1 < blockStates.length) {
				value |= (blockStates[longIndex + 1] & ((1L << spill) - 1L)) << (bitsPerEntry - spill);
			}
			if (value != 0L) nonAir++;
		}
		return new ParsedLitematic(
			nonAir,
			(Integer) position.get("x"),
			(Integer) position.get("y"),
			(Integer) position.get("z")
		);
	}

	private static Object readPayload(DataInputStream in, byte type) throws IOException {
		return switch (type) {
			case 0 -> null;
			case 3 -> in.readInt();
			case 4 -> in.readLong();
			case 8 -> in.readUTF();
			case 9 -> readList(in);
			case 10 -> readCompound(in);
			case 12 -> readLongArray(in);
			default -> throw new IOException("unsupported nbt type " + type);
		};
	}

	private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
		Map<String, Object> out = new LinkedHashMap<>();
		while (true) {
			byte type = in.readByte();
			if (type == 0) return out;
			String name = in.readUTF();
			out.put(name, readPayload(in, type));
		}
	}

	private static List<Object> readList(DataInputStream in) throws IOException {
		byte elementType = in.readByte();
		int size = in.readInt();
		List<Object> out = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			out.add(readPayload(in, elementType));
		}
		return out;
	}

	private static long[] readLongArray(DataInputStream in) throws IOException {
		int size = in.readInt();
		long[] out = new long[size];
		for (int i = 0; i < size; i++) out[i] = in.readLong();
		return out;
	}

	private static void assertEquals(Object expected, Object actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + ": expected=" + expected + " actual=" + actual);
		}
	}

	private static void deleteTree(Path dir) throws Exception {
		if (!Files.exists(dir)) return;
		try (var paths = Files.walk(dir)) {
			for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}

	private record ParsedLitematic(int nonAirBlocks, int positionX, int positionY, int positionZ) {
	}
}

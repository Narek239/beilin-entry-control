package us.beiyue.beilindataportability.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ExportPackageWriter {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private ExportPackageWriter() {
	}

	public static ExportArtifact writeZip(Path outputDir, ExportJob job, ExportBundle bundle) throws IOException {
		Files.createDirectories(outputDir);
		Path output = outputDir.resolve(zipFileName(job));
		try (OutputStream fileOut = Files.newOutputStream(output);
			 ZipOutputStream zip = new ZipOutputStream(fileOut, StandardCharsets.UTF_8)) {
			zip.putNextEntry(new ZipEntry("manifest.json"));
			zip.write(GSON.toJson(bundle.manifest.toManifestJson()).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();

			for (ComponentExport component : bundle.components) {
				String filename = component.summary.filename != null
					? component.summary.filename
					: String.format("%03d.litematic", component.summary.componentIndex);
				zip.putNextEntry(new ZipEntry(filename));
				LitematicWriter.write(component, "Beilin Data Portability", zip);
				zip.closeEntry();
			}
		}
		return new ExportArtifact(output, Files.size(output), sha256(output));
	}

	private static String zipFileName(ExportJob job) {
		String safeUser = (job.minecraftUsername == null ? "player" : job.minecraftUsername)
			.replaceAll("[^a-zA-Z0-9_.-]", "_");
		return "export-" + job.requestId + "-" + safeUser + "-" + Instant.now().toEpochMilli() + ".zip";
	}

	private static String sha256(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (OutputStream sink = OutputStream.nullOutputStream();
				 java.io.InputStream in = Files.newInputStream(path)) {
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) >= 0) {
					if (n == 0) continue;
					digest.update(buf, 0, n);
					sink.write(buf, 0, n);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("SHA-256 unavailable", e);
		}
	}
}

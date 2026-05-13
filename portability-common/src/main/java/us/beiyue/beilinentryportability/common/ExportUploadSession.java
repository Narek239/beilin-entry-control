package us.beiyue.beilinentryportability.common;

public final class ExportUploadSession {
	public final long uploadId;
	public final String objectKey;
	public final int partSizeBytes;

	public ExportUploadSession(long uploadId, String objectKey, int partSizeBytes) {
		this.uploadId = uploadId;
		this.objectKey = objectKey;
		this.partSizeBytes = partSizeBytes;
	}
}

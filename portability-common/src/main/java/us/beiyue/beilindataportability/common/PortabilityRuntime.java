package us.beiyue.beilindataportability.common;

import us.beiyue.beilinentrycontrol.common.log.CommonLogger;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PortabilityRuntime {
	private static final long STOP_FAIL_TIMEOUT_SECONDS = 2L;
	private static final String STOP_FAILURE_REASON = "ExportShutdown";

	private final PortabilityApiClient apiClient;
	private final CommonLogger log;
	private final BuildingIndexStore indexStore;
	private final WorldBlockReader worldBlockReader;
	private final Path artifactDir;
	private final int maxExportVolumeBlocks;
	private final ScheduledExecutorService scheduler;
	private final PortabilityBridge.Listener exportJobsListener = this::onExportJobs;
	private final Queue<ExportJob> pendingJobs = new ArrayDeque<>();
	private final Set<Long> acceptedJobIds = new HashSet<>();
	private final AtomicBoolean processing = new AtomicBoolean(false);
	private final AtomicBoolean stopped = new AtomicBoolean(false);
	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AtomicReference<CompletableFuture<Boolean>> currentJobFuture = new AtomicReference<>();
	private final AtomicReference<Long> currentProcessingJobId = new AtomicReference<>();
	private final AtomicReference<Long> currentClaimedJobId = new AtomicReference<>();

	public PortabilityRuntime(
		PortabilityApiClient apiClient,
		CommonLogger log,
		BuildingIndexStore indexStore,
		WorldBlockReader worldBlockReader,
		Path artifactDir,
		int maxExportVolumeBlocks
	) {
		this.apiClient = apiClient;
		this.log = log;
		this.indexStore = indexStore;
		this.worldBlockReader = worldBlockReader;
		this.artifactDir = artifactDir;
		this.maxExportVolumeBlocks = Math.max(1, maxExportVolumeBlocks);
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "beilin-data-portability");
			t.setDaemon(true);
			return t;
		});
	}

	public void start() {
		stopped.set(false);
		if (!started.compareAndSet(false, true)) return;
		PortabilityBridge.addListener(exportJobsListener);
		log.info("Beilin Data Portability export push listener started");
	}

	public void stop() {
		if (stopped.getAndSet(true)) return;
		PortabilityBridge.removeListener(exportJobsListener);
		pendingJobs.clear();
		acceptedJobIds.clear();
		Long processingJobId = currentProcessingJobId.getAndSet(null);
		Long claimedJobId = currentClaimedJobId.getAndSet(null);
		CompletableFuture<Boolean> future = currentJobFuture.getAndSet(null);
		if (future != null) {
			future.cancel(true);
		}
		processing.set(false);
		scheduler.shutdownNow();
		Long failJobId = claimedJobId != null ? claimedJobId : processingJobId;
		if (failJobId != null) {
			failJobDuringStop(failJobId);
		}
		apiClient.shutdownNow();
	}

	private void onExportJobs(List<ExportJob> jobs) {
		if (stopped.get() || jobs == null || jobs.isEmpty()) return;
		try {
			scheduler.execute(() -> {
				if (stopped.get()) return;
				if (indexStore == null) {
					log.info("Portability export queue has {} pending job(s)", jobs.size());
					return;
				}
				for (ExportJob job : jobs) {
					enqueueJob(job);
				}
				processNextJob();
			});
		} catch (RejectedExecutionException ignored) {
		}
	}

	private void enqueueJob(ExportJob job) {
		if (job == null || job.requestId <= 0) return;
		if (acceptedJobIds.add(job.requestId)) {
			pendingJobs.add(job);
		}
	}

	private void processNextJob() {
		if (stopped.get() || processing.get()) return;
		ExportJob next = pendingJobs.poll();
		if (next == null) return;
		processJob(next);
	}

	private void processJob(ExportJob job) {
		if (stopped.get()) return;
		if (!processing.compareAndSet(false, true)) return;
		currentProcessingJobId.set(job.requestId);
		CompletableFuture<Boolean> jobFuture = apiClient.claimJobAsync(job.requestId).thenComposeAsync(claimed -> {
			if (stopped.get()) {
				return CompletableFuture.completedFuture(false);
			}
			if (!claimed) {
				log.warn("Portability export job {} was not claimed; it may have been taken or changed state.", job.requestId);
				return CompletableFuture.completedFuture(true);
			}
			currentClaimedJobId.set(job.requestId);
			try {
				if (stopped.get()) return CompletableFuture.completedFuture(false);
				ExportBundle bundle = indexStore.buildExportBundle(job, worldBlockReader, maxExportVolumeBlocks);
				if (stopped.get()) return CompletableFuture.completedFuture(false);
				log.info("Portability export job {} manifest prepared: {} component(s), {} indexed region(s)",
					job.requestId, bundle.manifest.components.size(), bundle.manifest.totalIndexedRegions);
				ExportArtifact artifact = ExportPackageWriter.writeZip(artifactDir, job, bundle);
				log.info("Portability export job {} package written: {} ({} bytes)",
					job.requestId, artifact.path.toAbsolutePath(), artifact.bytes);
				return apiClient.submitManifestAsync(bundle.manifest)
					.thenCompose(ok -> {
						if (stopped.get()) return CompletableFuture.completedFuture(false);
						if (!ok) return CompletableFuture.completedFuture(false);
						return apiClient.uploadArtifactAsync(job.requestId, artifact)
							.thenCompose(upload -> stopped.get()
								? CompletableFuture.completedFuture(false)
								: apiClient.completeJobAsync(job.requestId, artifact, upload));
					});
			} catch (Exception e) {
				if (stopped.get()) return CompletableFuture.completedFuture(false);
				log.warn("Portability export job {} local package generation failed: {}", job.requestId, e.toString());
				apiClient.failJobAsync(job.requestId, "PackageGenerationFailed");
				return CompletableFuture.completedFuture(false);
			}
		}, scheduler).whenCompleteAsync((ok, ex) -> {
			try {
				if (stopped.get()) return;
				if (ex != null) {
					log.warn("Portability export job {} failed while uploading artifact: {}", job.requestId, ex.toString());
					apiClient.failJobAsync(job.requestId, "ArtifactUploadFailed");
				} else if (!Boolean.TRUE.equals(ok)) {
					log.warn("Portability export job {} was not accepted by the export service", job.requestId);
					apiClient.failJobAsync(job.requestId, "ExportDeliveryFailed");
				}
			} finally {
				currentClaimedJobId.compareAndSet(job.requestId, null);
				currentProcessingJobId.compareAndSet(job.requestId, null);
				currentJobFuture.set(null);
				acceptedJobIds.remove(job.requestId);
				processing.set(false);
				processNextJob();
			}
		}, scheduler);
		currentJobFuture.set(jobFuture);
		if (jobFuture.isDone()) {
			currentJobFuture.compareAndSet(jobFuture, null);
		}
	}

	private void failJobDuringStop(long requestId) {
		CompletableFuture<Boolean> fail = apiClient.failJobAsync(requestId, STOP_FAILURE_REASON);
		try {
			fail.get(STOP_FAIL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			fail.cancel(true);
			log.warn("Portability export job {} shutdown failure report timed out", requestId);
		} catch (Exception e) {
			log.warn("Portability export job {} shutdown failure report failed: {}", requestId, e.toString());
		}
	}
}

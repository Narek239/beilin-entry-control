package us.beiyue.beilinentryportability.common;

import us.beiyue.beilinentrycontrol.common.log.CommonLogger;
import us.beiyue.beilinentrycontrol.common.ws.BeilinWsEvents;

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
import java.util.concurrent.atomic.AtomicBoolean;

public final class PortabilityRuntime {
	private final PortabilityApiClient apiClient;
	private final CommonLogger log;
	private final BuildingIndexStore indexStore;
	private final WorldBlockReader worldBlockReader;
	private final boolean claimJobs;
	private final Path artifactDir;
	private final int maxExportVolumeBlocks;
	private final ScheduledExecutorService scheduler;
	private final PortabilityBridge.Listener exportJobsListener = this::onExportJobs;
	private final Queue<ExportJob> pendingJobs = new ArrayDeque<>();
	private final Set<Long> pendingJobIds = new HashSet<>();
	private final AtomicBoolean processing = new AtomicBoolean(false);
	private final AtomicBoolean stopped = new AtomicBoolean(false);
	private final AtomicBoolean started = new AtomicBoolean(false);

	public PortabilityRuntime(
		PortabilityApiClient apiClient,
		CommonLogger log,
		BuildingIndexStore indexStore,
		WorldBlockReader worldBlockReader,
		boolean claimJobs,
		Path artifactDir,
		int maxExportVolumeBlocks
	) {
		this.apiClient = apiClient;
		this.log = log;
		this.indexStore = indexStore;
		this.worldBlockReader = worldBlockReader;
		this.claimJobs = claimJobs;
		this.artifactDir = artifactDir;
		this.maxExportVolumeBlocks = Math.max(1, maxExportVolumeBlocks);
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "beilin-entry-portability");
			t.setDaemon(true);
			return t;
		});
	}

	public void start() {
		stopped.set(false);
		if (!started.compareAndSet(false, true)) return;
		PortabilityBridge.addListener(exportJobsListener);
		if (!BeilinWsEvents.requestExportJobs()) {
			log.info("Beilin Entry Portability waiting for WebSocket export job push");
		}
		apiClient.requestExportJobsPushAsync();
		log.info("Beilin Entry Portability export push listener started");
	}

	public void stop() {
		stopped.set(true);
		PortabilityBridge.removeListener(exportJobsListener);
		scheduler.shutdownNow();
	}

	private void onExportJobs(List<ExportJob> jobs) {
		if (stopped.get() || jobs == null || jobs.isEmpty()) return;
		try {
			scheduler.execute(() -> {
				if (stopped.get()) return;
				if (!claimJobs || indexStore == null) {
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
		if (pendingJobIds.add(job.requestId)) {
			pendingJobs.add(job);
		}
	}

	private void processNextJob() {
		if (stopped.get() || processing.get()) return;
		ExportJob next = pendingJobs.poll();
		if (next == null) return;
		pendingJobIds.remove(next.requestId);
		processJob(next);
	}

	private void processJob(ExportJob job) {
		if (stopped.get()) return;
		if (!processing.compareAndSet(false, true)) return;
		apiClient.claimJobAsync(job.requestId).thenComposeAsync(claimed -> {
			if (stopped.get()) {
				return CompletableFuture.completedFuture(false);
			}
			if (!claimed) {
				log.warn("Portability export job {} was not claimed; it may have been taken or changed state.", job.requestId);
				return CompletableFuture.completedFuture(false);
			}
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
						if (!ok) return CompletableFuture.completedFuture(false);
						return apiClient.uploadArtifactAsync(job.requestId, artifact)
							.thenCompose(upload -> apiClient.completeJobAsync(job.requestId, artifact, upload));
					});
			} catch (Exception e) {
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
				processing.set(false);
				processNextJob();
			}
		}, scheduler);
	}
}

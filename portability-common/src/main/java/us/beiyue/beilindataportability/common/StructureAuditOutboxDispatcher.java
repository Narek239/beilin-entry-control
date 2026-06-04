package us.beiyue.beilindataportability.common;

import us.beiyue.beilinentrycontrol.common.log.CommonLogger;
import us.beiyue.beilinentrycontrol.common.ws.BeilinWsEvents;
import us.beiyue.beilinentrycontrol.common.ws.StructureAuditAckListener;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StructureAuditOutboxDispatcher {
	private static final int BATCH_LIMIT = 50;
	private static final long FLUSH_INTERVAL_SECONDS = 5L;

	private final BuildingIndexStore store;
	private final CommonLogger log;
	private final ScheduledExecutorService scheduler;
	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AtomicBoolean stopped = new AtomicBoolean(false);
	private final StructureAuditAckListener ackListener = this::onAck;
	private ScheduledFuture<?> flushTask;

	public StructureAuditOutboxDispatcher(BuildingIndexStore store, CommonLogger log) {
		this.store = store;
		this.log = log;
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "beilin-structure-audit-outbox");
			t.setDaemon(true);
			return t;
		});
	}

	public void start() {
		if (store == null || !started.compareAndSet(false, true)) return;
		stopped.set(false);
		BeilinWsEvents.addStructureAuditAckListener(ackListener);
		flushTask = scheduler.scheduleWithFixedDelay(
			this::flushSafely,
			1L,
			FLUSH_INTERVAL_SECONDS,
			TimeUnit.SECONDS
		);
		log.info("Beilin Data Portability structure audit outbox dispatcher started");
	}

	public void stop() {
		if (stopped.getAndSet(true)) return;
		BeilinWsEvents.removeStructureAuditAckListener(ackListener);
		if (flushTask != null) {
			flushTask.cancel(false);
			flushTask = null;
		}
		scheduler.shutdownNow();
	}

	public void requestFlush() {
		if (stopped.get() || scheduler.isShutdown()) return;
		try {
			scheduler.execute(this::flushSafely);
		} catch (RejectedExecutionException ignored) {
		}
	}

	private void flushSafely() {
		if (stopped.get()) return;
		try {
			List<StructureAuditEvent> events = store.listPendingStructureAuditEvents(BATCH_LIMIT);
			if (events.isEmpty()) return;
			boolean sent = BeilinWsEvents.sendStructureAuditEvents(StructureAuditEvent.toWsMessage(events));
			if (sent) {
				log.debug("Beilin structure audit sent {} pending event(s)", events.size());
			}
		} catch (Exception e) {
			log.warn("Beilin structure audit outbox flush failed: {}", e.toString());
		}
	}

	private void onAck(List<String> eventIds) {
		if (eventIds == null || eventIds.isEmpty() || stopped.get()) return;
		try {
			store.deleteStructureAuditOutboxEvents(eventIds);
		} catch (Exception e) {
			log.warn("Beilin structure audit ack cleanup failed: {}", e.toString());
		}
	}
}

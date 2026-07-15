package com.sbshop.agent.api.sse;

import com.sbshop.agent.core.application.sync.SseBridgeCodec;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

/**
 * api JVM에서 Postgres LISTEN으로 worker의 SSE 브리지 알림을 소비한다(F-MISC-16).
 *
 * <p>{@link com.sbshop.agent.api.controller.SseNotificationController}의 @EventListener는 같은 JVM의
 * 이벤트만 받는다. worker 스케줄러가 발행한 {@code SyncCompletedEvent}는 worker의 NOTIFY 브리지가
 * {@link SseBridgeCodec#CHANNEL} 채널로 실어 보내고, 이 컴포넌트가 LISTEN으로 받아 이벤트를 재구성해
 * 로컬에 재발행한다. 그러면 기존 SSE 컨트롤러의 @EventListener가 클라이언트에 브로드캐스트한다.
 *
 * <p>전용 커넥션을 하나 잡아 홀드하며 데몬 스레드에서 블로킹 폴링한다. 커넥션이 끊기면 백오프 후
 * 재연결한다. api 로컬 이벤트(수동 트리거)는 NOTIFY를 타지 않으므로 이중 전달이 없다. Batch 이벤트는
 * 브리지 대상이 아니다(api JVM 실행이라 이미 SSE 전달됨).
 */
@Slf4j
@Component
public class SseListenBridge {

	private static final long POLL_TIMEOUT_MS = 10_000L;
	private static final long RECONNECT_BACKOFF_MS = 5_000L;

	private final DataSource dataSource;
	private final ApplicationEventPublisher eventPublisher;

	private volatile boolean running = true;
	private volatile Connection connection;
	private Thread listenThread;

	@Autowired
	public SseListenBridge(DataSource dataSource, ApplicationEventPublisher eventPublisher) {
		this.dataSource = dataSource;
		this.eventPublisher = eventPublisher;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		listenThread = new Thread(this::listenLoop, "sse-listen-bridge");
		listenThread.setDaemon(true);
		listenThread.start();
	}

	private void listenLoop() {
		while (running) {
			try {
				runListenSession();
			} catch (Exception e) {
				// 커넥션 끊김 등 모든 예외를 잡아 스레드가 죽지 않게 한다.
				if (running) {
					log.warn("SSE LISTEN 세션 종료, {}ms 후 재연결: {}", RECONNECT_BACKOFF_MS, e.getMessage());
					sleepBackoff();
				}
			}
		}
	}

	private void runListenSession() throws Exception {
		Connection conn = dataSource.getConnection();
		this.connection = conn;
		try {
			try (Statement st = conn.createStatement()) {
				st.execute("LISTEN " + SseBridgeCodec.CHANNEL);
			}
			PGConnection pgConn = conn.unwrap(PGConnection.class);
			log.info("SSE LISTEN 브리지 시작 (channel={})", SseBridgeCodec.CHANNEL);

			while (running) {
				PGNotification[] notifications = pgConn.getNotifications((int) POLL_TIMEOUT_MS);
				if (notifications == null) {
					continue;
				}
				for (PGNotification n : notifications) {
					handlePayload(n.getParameter());
				}
			}
		} finally {
			closeQuietly(conn);
			this.connection = null;
		}
	}

	/**
	 * NOTIFY 페이로드 한 건을 처리한다(테스트 seam). 유효하면 {@code SyncCompletedEvent}를 재구성해
	 * 재발행하고, 형식이 깨졌으면 조용히 무시한다. 개별 페이로드 처리 실패가 LISTEN 루프를 깨선 안 된다.
	 */
	void handlePayload(String payload) {
		try {
			SseBridgeCodec.Parsed parsed = SseBridgeCodec.parse(payload);
			if (parsed == null) {
				log.debug("SSE LISTEN 페이로드 무시(형식 오류): {}", payload);
				return;
			}
			eventPublisher.publishEvent(
				new com.sbshop.agent.core.application.order.event.SyncCompletedEvent(
					this, parsed.marketType(), parsed.success(), parsed.errorMessage()));
		} catch (Exception e) {
			log.warn("SSE LISTEN 페이로드 처리 실패: {}", e.getMessage());
		}
	}

	private void sleepBackoff() {
		try {
			Thread.sleep(RECONNECT_BACKOFF_MS);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
	}

	private void closeQuietly(Connection conn) {
		try {
			if (conn != null && !conn.isClosed()) {
				conn.close();
			}
		} catch (Exception e) {
			log.debug("SSE LISTEN 커넥션 종료 중 예외: {}", e.getMessage());
		}
	}

	@PreDestroy
	public void stop() {
		running = false;
		closeQuietly(connection);
		if (listenThread != null) {
			listenThread.interrupt();
		}
	}
}

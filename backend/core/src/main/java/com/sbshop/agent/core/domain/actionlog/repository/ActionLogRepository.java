package com.sbshop.agent.core.domain.actionlog.repository;

import com.sbshop.agent.core.domain.actionlog.ActionLog;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActionLogRepository extends JpaRepository<ActionLog, Long> {

	/** 최근 100건 시간 역순 */
	List<ActionLog> findTop100ByOrderByCreatedAtDesc();

	/** limit 파라미터 지원용 (Pageable로 개수 제어) */
	List<ActionLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

package com.sbshop.agent.core.domain.actionlog.repository;

import com.sbshop.agent.core.domain.actionlog.ActionLog;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActionLogRepository extends JpaRepository<ActionLog, Long> {

	List<ActionLog> findTop100ByOrderByCreatedAtDesc();

	List<ActionLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}

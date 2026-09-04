package com.sbshop.agent.core.domain.actionlog.repository;

import com.sbshop.agent.core.domain.actionlog.ActionLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActionLogRepository extends JpaRepository<ActionLog, Long> {

	List<ActionLog> findTop100ByOrderByCreatedAtDesc();

	List<ActionLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

	@Query("select count(a) from ActionLog a where a.actionType = :actionType and a.createdAt >= :since")
	long countTodayByActionType(@Param("actionType") String actionType, @Param("since") LocalDateTime since);
}

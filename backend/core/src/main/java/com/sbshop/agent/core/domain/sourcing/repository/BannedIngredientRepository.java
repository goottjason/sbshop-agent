package com.sbshop.agent.core.domain.sourcing.repository;

import com.sbshop.agent.core.domain.sourcing.BannedIngredient;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BannedIngredientRepository extends JpaRepository<BannedIngredient, Long> {

	Optional<BannedIngredient> findByNameKoAndNameEn(String nameKo, String nameEn);

	Optional<BannedIngredient> findFirstByNameKo(String nameKo);

	/** 현재 차단 중인 성분만 (해제일이 없거나 미래). */
	@Query("SELECT b FROM BannedIngredient b WHERE b.releasedOn IS NULL OR b.releasedOn > CURRENT_DATE")
	List<BannedIngredient> findAllActive();
}

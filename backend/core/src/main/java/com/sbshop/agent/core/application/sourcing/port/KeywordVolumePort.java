package com.sbshop.agent.core.application.sourcing.port;

import com.sbshop.agent.core.application.sourcing.dto.KeywordVolume;
import java.util.List;

public interface KeywordVolumePort {
	boolean isEnabled();

	List<KeywordVolume> lookup(String seedKeyword);
}

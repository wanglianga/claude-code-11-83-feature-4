package com.foodsafety.exchange.sampling;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CalibrationTotalRepository extends JpaRepository<CalibrationTotal, Long> {

  List<CalibrationTotal> findByFrameId(String frameId);
}

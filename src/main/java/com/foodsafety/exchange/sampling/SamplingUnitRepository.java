package com.foodsafety.exchange.sampling;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SamplingUnitRepository extends JpaRepository<SamplingUnit, String> {

  List<SamplingUnit> findByFrameIdOrderByUnitId(String frameId);
}

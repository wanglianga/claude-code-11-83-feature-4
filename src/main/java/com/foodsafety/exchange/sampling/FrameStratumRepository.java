package com.foodsafety.exchange.sampling;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FrameStratumRepository extends JpaRepository<FrameStratum, FrameStratum.Key> {

  List<FrameStratum> findByFrameId(String frameId);
}

package com.foodsafety.exchange.sampling;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LabReportRepository extends JpaRepository<LabReport, String> {

  List<LabReport> findByFrameIdOrderByIssuedAt(String frameId);

  List<LabReport> findByUnitIdOrderByIssuedAt(String unitId);
}

package com.vacantagent.network.data;

import com.vacantagent.network.entities.ExtractStatus;
import com.vacantagent.network.entities.ExtractVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExtractVersionRepository extends JpaRepository<ExtractVersion, Long> {
    Optional<ExtractVersion> findFirstByRegionAndStatusOrderByIdDesc(String region, ExtractStatus status);
}
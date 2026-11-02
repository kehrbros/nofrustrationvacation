package com.nfv.network.data;

import com.nfv.network.entities.ExtractStatus;
import com.nfv.network.entities.ExtractVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExtractVersionRepository extends JpaRepository<ExtractVersion, Long> {
    Optional<ExtractVersion> findFirstByRegionAndStatusOrderByIdDesc(String region, ExtractStatus status);
}
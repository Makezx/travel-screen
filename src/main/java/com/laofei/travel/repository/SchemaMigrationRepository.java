package com.laofei.travel.repository;

import com.laofei.travel.model.SchemaMigration;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchemaMigrationRepository extends JpaRepository<SchemaMigration, String> {

    /** 判断某次一次性迁移是否已执行（版本位） */
    boolean existsByName(String name);
}

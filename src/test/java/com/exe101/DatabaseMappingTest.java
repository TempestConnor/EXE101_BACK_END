package com.exe101;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Explicit live-database check: boot validates every mapping without DDL or DML. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIfEnvironmentVariable(named = "DB_URL", matches = ".+")
class DatabaseMappingTest {
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private EntityManager entityManager;

    @Test
    void mapsAllApplicationTables() {
        assertEquals(45, entityManagerFactory.getMetamodel().getEntities().size());
    }

    @Test
    @Transactional(readOnly = true)
    void canSelectEveryEntity() {
        for (var entity : entityManagerFactory.getMetamodel().getEntities()) {
            entityManager.createQuery("select e from " + entity.getName() + " e", entity.getJavaType())
                    .setMaxResults(1).getResultList();
        }
    }
}

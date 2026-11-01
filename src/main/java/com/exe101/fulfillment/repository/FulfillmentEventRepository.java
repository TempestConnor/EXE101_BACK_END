package com.exe101.fulfillment.repository;

import com.exe101.entity.FulfillmentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FulfillmentEventRepository extends JpaRepository<FulfillmentEvent, Long> {
}

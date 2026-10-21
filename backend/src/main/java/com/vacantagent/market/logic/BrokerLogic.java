package com.vacantagent.market.logic;

import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.entities.ProviderOffer;
import jakarta.annotation.Nullable;
import org.springframework.stereotype.Service;

@Service
public class BrokerLogic implements IBrokerLogic {

    public void acceptOrder(OrderDto orderDto) {
        // TODO implement
    }

    @Nullable
    private ProviderOffer matchOrderWithOffer(long orderId) {
        return null;    // TODO implement
    }
}

package com.vacantagent.market.logic;

import com.vacantagent.market.dto.OrderDto;

public interface IBrokerLogic {
    void acceptOrder(OrderDto orderDto);
}

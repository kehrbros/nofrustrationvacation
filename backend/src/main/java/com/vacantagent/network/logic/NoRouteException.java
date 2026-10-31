package com.vacantagent.network.logic;

import com.vacantagent.common.errorhandling.BusinessException;

/** No route: a point is outside every region or too far from a road, or the points are not connected. */
public class NoRouteException extends BusinessException {
    public NoRouteException(String message) {
        super(message);
    }
}
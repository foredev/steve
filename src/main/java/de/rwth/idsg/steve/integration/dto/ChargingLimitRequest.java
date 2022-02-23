package de.rwth.idsg.steve.integration.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChargingLimitRequest {
    private double limit;
    private String unit;
}

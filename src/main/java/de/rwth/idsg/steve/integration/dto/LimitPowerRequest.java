package de.rwth.idsg.steve.integration.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LimitPowerRequest {
    private int powerLimitInWatt;
}

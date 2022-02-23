package de.rwth.idsg.steve.integration.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChargingLimitResponse {
    private boolean success;
    private String error;

    public ChargingLimitResponse(boolean success) {
        this.success = success;
    }

    public ChargingLimitResponse(boolean success, String error) {
        this.success = success;
        this.error = error;
    }
}

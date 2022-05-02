package de.rwth.idsg.steve.integration.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChargingProfileResponse {
    private boolean success;
    private String error;

    public ChargingProfileResponse(boolean success) {
        this.success = success;
    }

    public ChargingProfileResponse(boolean success, String error) {
        this.success = success;
        this.error = error;
    }
}

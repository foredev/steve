package de.rwth.idsg.steve.integration.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChargingProfileResponse {
    private boolean success;
    private String error;
    private int chargingProfilePk;

    public ChargingProfileResponse(boolean success, int chargingProfilePk) {
        this.success = success;
        this.chargingProfilePk = chargingProfilePk;
    }

    public ChargingProfileResponse(boolean success, String error) {
        this.success = success;
        this.error = error;

    }
}

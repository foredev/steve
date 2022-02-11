package de.rwth.idsg.steve.integration.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LimitPowerResponse {
    private boolean success;
    private String error;

    public LimitPowerResponse(boolean success) {
        this.success = success;
    }

    public LimitPowerResponse(boolean success, String error) {
        this.success = success;
        this.error = error;
    }
}

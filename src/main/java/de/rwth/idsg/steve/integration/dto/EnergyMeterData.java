package de.rwth.idsg.steve.integration.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;

@Getter
@Setter
public class EnergyMeterData {
    private List<Double> current;
    private double energy;
    private double power;
    private List<Double> voltage;
    private double frequency;
    private Date timestamp;

    public boolean eligibleToSend() {
        if(!current.isEmpty() || energy != 0 || power != 0 ||
                !voltage.isEmpty() || frequency != 0) {
            return true;
        } else {
            return false;
        }
    }
}

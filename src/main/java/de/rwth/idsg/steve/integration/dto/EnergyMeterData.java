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
}

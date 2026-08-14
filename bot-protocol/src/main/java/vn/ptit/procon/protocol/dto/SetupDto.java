package vn.ptit.procon.protocol.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SetupDto(
        MapDto map,
        List<SpotDto> spots,
        List<Integer> agents,
        int[] daySteps,
        Integer fuelLimits) {
}
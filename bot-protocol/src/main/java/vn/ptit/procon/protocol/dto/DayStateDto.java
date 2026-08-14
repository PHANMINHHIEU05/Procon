package vn.ptit.procon.protocol.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DayStateDto(
        Integer day,
        List<AgentStateDto> agents,
        JsonNode others,
        List<TrafficDto> traffics) {
}
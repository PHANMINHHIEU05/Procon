package vn.ptit.procon.protocol.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SpotDto(JsonNode brand, Integer pos, Integer stocks) {
}
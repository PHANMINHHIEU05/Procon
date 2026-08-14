package vn.ptit.procon.protocol.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TrafficDto(Integer pos, Integer status) {
}
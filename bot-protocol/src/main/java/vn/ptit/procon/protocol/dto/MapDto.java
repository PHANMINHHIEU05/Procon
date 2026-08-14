package vn.ptit.procon.protocol.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MapDto(Integer width, Integer height, int[][] cells) {
}
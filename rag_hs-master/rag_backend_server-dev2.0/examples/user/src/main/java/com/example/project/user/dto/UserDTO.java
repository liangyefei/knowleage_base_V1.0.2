package com.example.project.user.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

public record UserDTO(String id, String username,
                      @JsonFormat(shape= JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")LocalDateTime createdAt,
                      @JsonFormat(shape= JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")LocalDateTime lastLogin) {
}

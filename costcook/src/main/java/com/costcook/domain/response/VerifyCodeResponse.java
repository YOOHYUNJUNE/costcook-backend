package com.costcook.domain.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class VerifyCodeResponse {
    private boolean isVerified;
    private String message;
}
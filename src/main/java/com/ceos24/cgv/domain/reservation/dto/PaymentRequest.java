package com.ceos24.cgv.domain.reservation.dto;

import com.ceos24.cgv.global.common.PaymentResult;
import jakarta.validation.constraints.NotNull;

public record PaymentRequest(@NotNull PaymentResult result) {
}

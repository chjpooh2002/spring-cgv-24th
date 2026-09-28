package com.ceos24.cgv.domain.store.dto;

import com.ceos24.cgv.global.common.PaymentResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

// 매점은 결제를 따로 호출하지 않고 구매 요청 한 번으로 끝나므로 mock 결제 결과를 여기서 받는다.
// 결과 값은 예매 결제와 같은 enum을 쓴다. 구매자는 본문이 아니라 토큰에서 정한다.
public record PurchaseCreateRequest(
        @NotNull Long branchId,
        @NotEmpty @Valid List<Item> items,
        @NotNull PaymentResult paymentResult
) {
    public record Item(
            @NotNull Long productId,
            @NotNull @Min(1) Integer quantity
    ) {
    }
}

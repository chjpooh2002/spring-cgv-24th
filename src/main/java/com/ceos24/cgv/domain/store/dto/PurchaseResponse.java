package com.ceos24.cgv.domain.store.dto;

import com.ceos24.cgv.domain.store.entity.Purchase;
import com.ceos24.cgv.domain.store.entity.PurchaseProduct;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

public record PurchaseResponse(
        Long purchaseId,
        Long branchId,
        String branchName,
        int totalPrice,
        LocalDateTime purchasedAt,
        List<Item> items
) {
    public record Item(
            Long productId,
            String name,
            int quantity,
            int unitPrice,
            int subtotal
    ) {
        private static Item from(PurchaseProduct item) {
            return new Item(
                    item.getProduct().getId(),
                    item.getProduct().getName(),
                    item.getQuantity(),
                    item.getUnitPrice(),
                    item.getUnitPrice() * item.getQuantity()
            );
        }

        private static Item from(PurchaseHistoryRow row) {
            return new Item(
                    row.productId(),
                    row.productName(),
                    row.quantity(),
                    row.unitPrice(),
                    row.unitPrice() * row.quantity()
            );
        }
    }

    public static PurchaseResponse from(Purchase purchase) {
        return new PurchaseResponse(
                purchase.getId(),
                purchase.getBranch().getId(),
                purchase.getBranch().getName(),
                purchase.getTotalPrice(),
                purchase.getPurchasedAt(),
                purchase.getItems().stream().map(Item::from).toList()
        );
    }

    // 행은 구매 id 내림차순으로 온다. 삽입 순서를 지키는 맵으로 묶어 그 순서를 유지한다.
    public static List<PurchaseResponse> listOf(List<PurchaseHistoryRow> rows) {
        return rows.stream()
                .collect(Collectors.groupingBy(PurchaseHistoryRow::purchaseId, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(PurchaseResponse::of)
                .toList();
    }

    private static PurchaseResponse of(List<PurchaseHistoryRow> rows) {
        PurchaseHistoryRow header = rows.getFirst();
        return new PurchaseResponse(
                header.purchaseId(),
                header.branchId(),
                header.branchName(),
                header.totalPrice(),
                header.purchasedAt(),
                rows.stream().map(Item::from).toList()
        );
    }
}

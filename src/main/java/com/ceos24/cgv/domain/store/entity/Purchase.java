package com.ceos24.cgv.domain.store.entity;

import com.ceos24.cgv.domain.branch.entity.Branch;
import com.ceos24.cgv.global.entity.BaseTimeEntity;
import com.ceos24.cgv.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Purchase extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "purchase_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(nullable = false)
    private int totalPrice;

    @Column(nullable = false)
    private LocalDateTime purchasedAt;

    @OneToMany(mappedBy = "purchase", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PurchaseProduct> items = new ArrayList<>();

    @Builder
    private Purchase(User user, Branch branch) {
        this.user = user;
        this.branch = branch;
        this.totalPrice = 0;
        this.purchasedAt = LocalDateTime.now();
    }

    // 자식 항목 합계와 어긋나지 않도록 이 메서드 안에서만 총액을 갱신한다.
    // 단가는 지금 상품 가격을 복사한다. 호출자에게 받으면 상품 가격과 다른 값이 들어올 틈이 생긴다.
    public void addItem(Product product, int quantity) {
        int unitPrice = product.getPrice();
        PurchaseProduct item = PurchaseProduct.builder()
                .purchase(this)
                .product(product)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .build();
        this.items.add(item);
        this.totalPrice += unitPrice * quantity;
    }
}

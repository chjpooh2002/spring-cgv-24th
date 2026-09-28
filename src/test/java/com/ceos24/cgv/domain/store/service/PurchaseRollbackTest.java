package com.ceos24.cgv.domain.store.service;

import com.ceos24.cgv.domain.branch.entity.Branch;
import com.ceos24.cgv.domain.store.dto.PurchaseCreateRequest;
import com.ceos24.cgv.domain.store.entity.Product;
import com.ceos24.cgv.domain.store.entity.Stock;
import com.ceos24.cgv.domain.user.entity.User;
import com.ceos24.cgv.global.common.PaymentResult;
import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.exception.ErrorCode;
import com.ceos24.cgv.support.TestFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실패한 구매가 재고를 하나도 건드리지 않는지는 실제로 롤백이 일어나야 확인된다.
// @Transactional 테스트에서는 롤백이 테스트 끝까지 미뤄져 차감된 값이 영속성 컨텍스트에 남아 보인다.
@SpringBootTest
class PurchaseRollbackTest {

    @Autowired PurchaseService purchaseService;
    @Autowired TransactionTemplate transactionTemplate;

    @PersistenceContext EntityManager em;

    private Long userId;
    private Long branchId;
    private Long popcornId;
    private Long colaId;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            User user = TestFixtures.user("rollbackBuyer");
            em.persist(user);
            Branch branch = TestFixtures.branch("롤백지점");
            em.persist(branch);
            Product popcorn = TestFixtures.product("팝콘", 5000);
            em.persist(popcorn);
            Product cola = TestFixtures.product("콜라", 3000);
            em.persist(cola);
            em.persist(TestFixtures.stock(branch, popcorn, 10));
            em.persist(TestFixtures.stock(branch, cola, 3));

            userId = user.getId();
            branchId = branch.getId();
            popcornId = popcorn.getId();
            colaId = cola.getId();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            em.createQuery("delete from PurchaseProduct").executeUpdate();
            em.createQuery("delete from Purchase").executeUpdate();
            em.createQuery("delete from Stock").executeUpdate();
            em.createQuery("delete from Product").executeUpdate();
            em.createQuery("delete from Branch").executeUpdate();
            em.createQuery("delete from User").executeUpdate();
        });
    }

    @Test
    void 한_상품이라도_재고가_모자라면_다른_상품도_차감되지_않는다() {
        // 팝콘은 먼저 잠기고 차감된 뒤 콜라에서 실패한다. 부분 성공이 남으면 팝콘이 8이 된다.
        assertThatThrownBy(() -> purchaseService.purchase(userId, request(PaymentResult.SUCCESS,
                new PurchaseCreateRequest.Item(popcornId, 2),
                new PurchaseCreateRequest.Item(colaId, 3))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.OUT_OF_STOCK);

        assertThat(quantityOf(popcornId)).isEqualTo(10);
        assertThat(quantityOf(colaId)).isEqualTo(3);
        assertThat(purchaseCount()).isZero();
    }

    @Test
    void 결제가_실패하면_차감한_재고가_원복되고_구매_기록도_없다() {
        assertThatThrownBy(() -> purchaseService.purchase(userId, request(PaymentResult.FAILURE,
                new PurchaseCreateRequest.Item(popcornId, 2))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PURCHASE_PAYMENT_FAILED);

        assertThat(quantityOf(popcornId)).isEqualTo(10);
        assertThat(purchaseCount()).isZero();
    }

    @Test
    void 엔티티를_거치지_않는_쓰기도_DB_CHECK_제약이_막는다() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                em.createQuery("update Stock s set s.quantity = 0 where s.product.id = :productId")
                        .setParameter("productId", popcornId)
                        .executeUpdate()))
                // EntityManager를 직접 쓰면 리포지토리 프록시의 예외 번역을 거치지 않아 Hibernate 예외 그대로 온다.
                .isInstanceOf(ConstraintViolationException.class)
                .message().containsIgnoringCase("ck_stock_quantity_min");

        assertThat(quantityOf(popcornId)).isEqualTo(10);
    }

    private PurchaseCreateRequest request(PaymentResult result, PurchaseCreateRequest.Item... items) {
        return new PurchaseCreateRequest(branchId, List.of(items), result);
    }

    private int quantityOf(Long productId) {
        return transactionTemplate.execute(status -> em.createQuery(
                        "select s from Stock s where s.branch.id = :branchId and s.product.id = :productId",
                        Stock.class)
                .setParameter("branchId", branchId)
                .setParameter("productId", productId)
                .getSingleResult()
                .getQuantity());
    }

    private long purchaseCount() {
        return transactionTemplate.execute(status -> em.createQuery(
                "select count(p) from Purchase p", Long.class).getSingleResult());
    }
}

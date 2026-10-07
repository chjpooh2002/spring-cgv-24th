package com.ceos24.cgv.domain.store.service;

import com.ceos24.cgv.domain.branch.entity.Branch;
import com.ceos24.cgv.domain.branch.repository.BranchRepository;
import com.ceos24.cgv.domain.store.dto.PurchaseCreateRequest;
import com.ceos24.cgv.domain.store.dto.PurchaseCreateRequest.Item;
import com.ceos24.cgv.domain.store.dto.PurchaseResponse;
import com.ceos24.cgv.domain.store.entity.Product;
import com.ceos24.cgv.domain.store.entity.Purchase;
import com.ceos24.cgv.domain.store.entity.Stock;
import com.ceos24.cgv.domain.store.repository.ProductRepository;
import com.ceos24.cgv.domain.store.repository.PurchaseRepository;
import com.ceos24.cgv.domain.store.repository.StockRepository;
import com.ceos24.cgv.domain.user.entity.User;
import com.ceos24.cgv.domain.user.repository.UserRepository;
import com.ceos24.cgv.global.common.PaymentResult;
import com.ceos24.cgv.global.exception.CustomException;
import com.ceos24.cgv.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseService {

    private final PurchaseRepository purchaseRepository;
    private final StockRepository stockRepository;
    private final ProductRepository productRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    // 재고 확보부터 저장까지 한 트랜잭션이다. 어느 단계에서 예외가 나도 차감이 전부 롤백되어
    // 부분 성공이 없고, 잡았던 재고 행 락도 함께 풀린다.
    @Transactional
    public PurchaseResponse purchase(Long userId, PurchaseCreateRequest req) {
        validateNoDuplicateProducts(req.items());
        User user = userRepository.getByIdOrThrow(userId);
        Branch branch = getOperatingBranch(req.branchId());
        // 가격·이름은 여기서 읽고, 재고 락 쿼리는 product를 건드리지 않는다
        Map<Long, Product> products = findProducts(req.items());

        Purchase purchase = Purchase.builder()
                .user(user).branch(branch).now(LocalDateTime.now(clock)).build();
        deductStocks(purchase, branch.getId(), req.items(), products);

        // mock 결제. 재고를 확보한 뒤에 판정해야 결제만 되고 품절인 경우가 생기지 않는다.
        // 예매와 달리 실패 시 남길 것이 없으므로 전부 롤백한다.
        if (req.paymentResult() == PaymentResult.FAILURE) {
            throw new CustomException(ErrorCode.PURCHASE_PAYMENT_FAILED);
        }

        return PurchaseResponse.from(purchaseRepository.save(purchase));
    }

    // 없는 사용자를 빈 목록으로 돌려주면 잘못된 id가 "구매 내역 없음"으로 숨는다.
    public List<PurchaseResponse> findHistory(Long userId) {
        userRepository.validateExists(userId);
        return PurchaseResponse.listOf(purchaseRepository.findHistoryRowsByUserId(userId));
    }

    private void validateNoDuplicateProducts(List<Item> items) {
        if (items.stream().map(Item::productId).distinct().count() != items.size()) {
            throw new CustomException(ErrorCode.DUPLICATE_PRODUCT_IN_REQUEST);
        }
    }

    private Branch getOperatingBranch(Long branchId) {
        Branch branch = branchRepository.getByIdOrThrow(branchId);
        if (!branch.isOperating()) {
            throw new CustomException(ErrorCode.BRANCH_NOT_OPERATING);
        }
        return branch;
    }

    private Map<Long, Product> findProducts(List<Item> items) {
        List<Long> productIds = items.stream().map(Item::productId).toList();
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        if (products.size() != productIds.size()) {
            throw new CustomException(ErrorCode.PRODUCT_NOT_FOUND);
        }
        return products;
    }

    // 상품 id 오름차순으로 한 건씩 잠가 획득 순서를 코드로 고정한다.
    // [A,B]와 [B,A] 주문이 서로를 물고 도는 데드락이 여기서 사라진다.
    private void deductStocks(Purchase purchase, Long branchId, List<Item> items, Map<Long, Product> products) {
        List<Item> lockOrder = items.stream()
                .sorted(Comparator.comparing(Item::productId))
                .toList();
        try {
            for (Item item : lockOrder) {
                Stock stock = stockRepository.findByBranchIdAndProductIdForUpdate(branchId, item.productId())
                        // 재고 행이 없으면 그 극장에서 팔지 않는 상품이다. 고객에게는 품절과 같다
                        .orElseThrow(() -> new CustomException(ErrorCode.OUT_OF_STOCK));
                stock.decrease(item.quantity());
                purchase.addItem(products.get(item.productId()), item.quantity());
            }
        } catch (ConcurrencyFailureException e) {
            // 락 대기 타임아웃. 품절이라는 뜻이 아니라 판정하지 못했다는 뜻이라 재시도를 안내한다.
            throw new CustomException(ErrorCode.STOCK_LOCK_CONFLICT);
        }
    }
}

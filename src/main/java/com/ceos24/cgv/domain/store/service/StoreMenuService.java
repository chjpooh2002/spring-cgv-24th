package com.ceos24.cgv.domain.store.service;

import com.ceos24.cgv.domain.branch.repository.BranchRepository;
import com.ceos24.cgv.domain.store.dto.StoreMenuResponse;
import com.ceos24.cgv.domain.store.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreMenuService {

    private final StockRepository stockRepository;
    private final BranchRepository branchRepository;

    // 메뉴는 재고 행 기준이다. 재고 행이 없는 상품은 그 극장에서 팔 수 없으므로 내리지 않는다.
    // 휴관 지점도 메뉴는 보여준다. 지점 상세가 상태로 거르지 않는 것과 같은 정책이다.
    public List<StoreMenuResponse> findMenu(Long branchId) {
        branchRepository.validateExists(branchId);
        return stockRepository.findMenuByBranchId(branchId).stream()
                .map(StoreMenuResponse::from)
                .toList();
    }
}

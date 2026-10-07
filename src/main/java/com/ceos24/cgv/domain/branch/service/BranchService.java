package com.ceos24.cgv.domain.branch.service;

import com.ceos24.cgv.domain.branch.dto.BranchDetailResponse;
import com.ceos24.cgv.domain.branch.dto.BranchResponse;
import com.ceos24.cgv.domain.branch.dto.RegionResponse;
import com.ceos24.cgv.domain.branch.entity.Branch;
import com.ceos24.cgv.domain.branch.entity.BranchStatus;
import com.ceos24.cgv.domain.branch.entity.Region;
import com.ceos24.cgv.domain.branch.entity.Theater;
import com.ceos24.cgv.domain.branch.repository.BranchRepository;
import com.ceos24.cgv.domain.branch.repository.BranchRepository.RegionCount;
import com.ceos24.cgv.domain.branch.repository.TheaterRepository;
import com.ceos24.cgv.domain.branch.repository.TheaterRepository.BranchTheaterType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BranchService {

    private static final BranchStatus EXCLUDED_FROM_LIST = BranchStatus.CLOSED;

    private final BranchRepository branchRepository;
    private final TheaterRepository theaterRepository;

    public List<BranchResponse> search(Region region, String keyword) {
        List<Branch> branches = findBranches(region, keyword);
        Map<Long, List<String>> specialTypes = specialTypesByBranchId(branches);

        return branches.stream()
                .map(branch -> BranchResponse.from(
                        branch, specialTypes.getOrDefault(branch.getId(), List.of())))
                .toList();
    }

    // 지역 탭은 enum 선언 순서가 곧 노출 순서다. 집계 결과에 없는 지역은 0으로 채운다.
    public List<RegionResponse> findRegions() {
        Map<Region, Long> countByRegion = branchRepository.countByRegion(EXCLUDED_FROM_LIST).stream()
                .collect(Collectors.toMap(RegionCount::getRegion, RegionCount::getBranchCount));

        return Arrays.stream(Region.values())
                .map(region -> RegionResponse.of(region, countByRegion.getOrDefault(region, 0L)))
                .toList();
    }

    // 상세는 상태로 거르지 않는다. 폐관 지점 링크로 들어와도 "운영종료"를 보여주는 편이 404보다 낫다.
    public BranchDetailResponse findById(Long id) {
        Branch branch = branchRepository.getByIdOrThrow(id);
        List<Theater> theaters = theaterRepository.findByBranchId(id);
        return BranchDetailResponse.from(branch, theaters);
    }

    // 검색어가 있으면 지역 탭을 무시한다. 실제 화면에서도 검색은 탭 선택을 초기화한다.
    private List<Branch> findBranches(Region region, String keyword) {
        if (StringUtils.hasText(keyword)) {
            return branchRepository.searchByKeyword(
                    keyword, Region.searchByKeyword(keyword), EXCLUDED_FROM_LIST);
        }
        if (region != null) {
            return branchRepository.findByRegion(region, EXCLUDED_FROM_LIST);
        }
        return branchRepository.findAllListed(EXCLUDED_FROM_LIST);
    }

    // 일반관은 라벨을 달지 않는다. 특별관이 없는 지점은 빈 목록이 된다.
    private Map<Long, List<String>> specialTypesByBranchId(List<Branch> branches) {
        if (branches.isEmpty()) {
            return Map.of();
        }
        List<Long> branchIds = branches.stream().map(Branch::getId).toList();

        // 한 지점이 특별관을 여러 종류 보유할 수 있어, GROUP BY 결과 순서에
        // 라벨 순서가 좌우되지 않도록 TheaterType 선언 순서로 고정한다.
        return theaterRepository.findTheaterTypesByBranchIds(branchIds).stream()
                .filter(row -> row.getTheaterType().isSpecial())
                .sorted(Comparator.comparing(BranchTheaterType::getTheaterType))
                .collect(Collectors.groupingBy(
                        BranchTheaterType::getBranchId,
                        Collectors.mapping(
                                row -> row.getTheaterType().getDisplayName(),
                                Collectors.toList())));
    }
}

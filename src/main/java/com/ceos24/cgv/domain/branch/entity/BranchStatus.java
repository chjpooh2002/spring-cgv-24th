package com.ceos24.cgv.domain.branch.entity;

// 운영 여부를 boolean으로 두면 "임시휴업"과 "운영종료"를 구분하지 못한다.
// 둘은 목록 노출 여부가 서로 달라 구분이 실제 로직에 필요하다.
public enum BranchStatus {

    OPEN("운영중"),
    TEMPORARILY_CLOSED("임시휴업"),
    CLOSED("운영종료");

    private final String displayName;

    BranchStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }

    public boolean isOperating() {
        return this == OPEN;
    }
}

package com.ceos24.cgv.domain.branch.entity;

import com.ceos24.cgv.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Branch extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "branch_id")
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private String address;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Region region;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BranchStatus status;

    // 교통·주차 안내가 줄바꿈 포함 수백~수천 자라 varchar로 감당하기 어렵다.
    // @Lob은 MySQL에서 LONGTEXT(4GB)로 매핑되어 과하므로 TEXT(64KB)를 직접 지정한다.
    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Builder
    private Branch(String name, String address, Region region, BranchStatus status,
                   String description, String imageUrl) {
        this.name = name;
        this.address = address;
        this.region = region;
        this.status = status != null ? status : BranchStatus.OPEN;
        this.description = description;
        this.imageUrl = imageUrl;
    }

    public boolean isOperating() {
        return status.isOperating();
    }
}

package com.sparta.delivery.domain.review.repository;

import com.sparta.delivery.domain.review.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID>, ReviewRepositoryCustom {
	boolean existsByOrderId(UUID id);

	// Review 엔티티에는 @SQLRestriction("is_deleted = false")가 걸려 있어서
	// existsByOrderId()는 "삭제되지 않은 리뷰"만 본다.
	// 반면 DB의 UNIQUE 제약은 소프트 딜리트된 행까지 포함해서 검사하므로,
	// 사전 검증과 DB 제약의 기준을 맞추기 위해 삭제 여부와 무관하게 세는 네이티브 쿼리를 둔다.
	@Query(value = "SELECT COUNT(*) FROM p_reviews WHERE order_id = :orderId", nativeQuery = true)
	long countByOrderIdIncludingDeleted(@Param("orderId") UUID orderId);
	Long countByRestaurantIdAndIsDeletedFalse(UUID id);

	@Query("SELECT COALESCE(AVG(r.rating), 0.0) " +
		"FROM Review r " +
		"WHERE r.restaurant.id = :restaurantId " +
		"AND r.isDeleted = false")
	Double calculateAverageRatingByRestaurantId(@Param("restaurantId") UUID id); // Double인 이유: 리뷰가 아얘 없으면 결과가 0이 아니라 Null
	// 따라서 기존의 double은 null을 담을수 없음 -> Double은 null을 지정한 디폴트값 0.0으로 변환
}

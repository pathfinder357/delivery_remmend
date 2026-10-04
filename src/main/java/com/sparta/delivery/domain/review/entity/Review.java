package com.sparta.delivery.domain.review.entity;

import com.sparta.delivery.domain.order.entity.Order;
import com.sparta.delivery.domain.restaurant.entity.Restaurant;
import com.sparta.delivery.domain.user.entity.User;
import com.sparta.delivery.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;

import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(
	name = "p_reviews",
	// 하나의 주문에는 리뷰가 하나만 존재해야 한다.
	// 애플리케이션 사전 검증(exists 조회)은 동시 요청에서 둘 다 통과할 수 있으므로
	// 최종 정합성은 DB UNIQUE 제약이 보장한다.
	uniqueConstraints = @UniqueConstraint(name = "uk_reviews_order_id", columnNames = "order_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE p_reviews SET is_deleted = true, deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class Review extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_id", nullable = false, unique = true)
	private Order order;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "restaurant_id", nullable = false)
	private Restaurant restaurant;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "customer_id", nullable = false)
	private User customer;

	@Column(nullable = false)
	private Integer rating;

	@Column(columnDefinition = "TEXT")
	private String content;

	@Version
	private Long version; // optimistic Lock
	// 리뷰 조회할때 버전 확인 -> 수정 where id =? And version =1(업데이트 쿼리)
	// 1 -> 2 로 수정
	// 만약 동시에 2명? -> 먼저 0.0000001초라도 먼저 커밋이 되는쪽이 2가 되기때문에
	// where 조건에서 걸러지면서 업데이트 실패되고 에러가 터지면서 rollback
	// 그럼 롤백된 트랜잭션을 결국 사용자에게 알려줄것인가? 아니면 롤백된것을 궁극적으로 다시
	// 적용되게 할지 멘토링필요

	@Builder
	public Review(Order order, Restaurant restaurant, User customer, Integer rating, String content) {
		this.order = order;
		this.restaurant = restaurant;
		this.customer = customer;
		this.rating = rating;
		this.content = content;
	}

	// 코드 리펙토링 (엔티티 객체 생성 하나하나 설정값 넣어서 하기 귀찮음때 사용)
	public static Review create(Order order, Integer rating, String content) {
		return Review.builder()
			.order(order)
			.restaurant(order.getRestaurant())
			.customer(order.getCustomer())
			.rating(rating)
			.content(content).
			build();
	}

	public void updateContentAndRating(String content, Integer rating) {
		this.content = content;
		this.rating = rating;
	}
}
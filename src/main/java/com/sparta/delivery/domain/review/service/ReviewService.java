package com.sparta.delivery.domain.review.service;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sparta.delivery.domain.order.entity.Order;
import com.sparta.delivery.domain.order.repository.OrderRepository;
import com.sparta.delivery.domain.restaurant.entity.Restaurant;
import com.sparta.delivery.domain.restaurant.repository.RestaurantRepository;
import com.sparta.delivery.domain.review.dto.ReviewRequestDto;
import com.sparta.delivery.domain.review.dto.ReviewResponseDto;
import com.sparta.delivery.domain.review.dto.ReviewSearchCondition;
import com.sparta.delivery.domain.review.entity.Review;
import com.sparta.delivery.domain.review.repository.ReviewRepository;
import com.sparta.delivery.global.common.Enums;
import com.sparta.delivery.global.exception.DuplicateResourceException;
import com.sparta.delivery.global.exception.ResourceNotFoundException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "Review API")
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

	private final ReviewRepository reviewRepository;
	private final OrderRepository orderRepository;
	private final RestaurantRepository restaurantRepository;

	@Transactional
	public ReviewResponseDto createReview(UUID orderId, @Valid ReviewRequestDto request, Long customerId) {

		// 1) 주문 존재 검증 (없는 리소스는 404)
		Order order = orderRepository.findById(orderId)
			.orElseThrow(() -> new ResourceNotFoundException("주문을 찾을 수 없습니다."));

		// 2) 소유권 검증 (다른 사람의 주문 정보를 상태 메시지로 흘리지 않기 위해 상태 검증보다 먼저 수행)
		if (!order.getCustomer().getId().equals(customerId)) {
			throw new AccessDeniedException("자신의 주문에서만 리뷰를 작성할 수 있습니다.");
		}

		// 3) 주문 상태 검증 (규칙 위반은 409)
		if (order.getOrderStatus() != Enums.OrderStatus.COMPLETED) {
			throw new IllegalStateException("배달이 완료된 주문만 리뷰를 작성할 수 있습니다.");
		}

		// 4) 중복 리뷰 사전 검증
		//    - 친절한 에러 메시지를 주기 위한 "1차 방어선"일 뿐, 동시 요청은 둘 다 통과할 수 있다.
		//    - 삭제된 리뷰까지 포함해서 세는 이유: DB UNIQUE 제약이 소프트 딜리트 행도 포함해 검사하기 때문이다.
		//      (= 리뷰를 삭제해도 같은 주문으로 다시 작성할 수 없다는 정책을 코드로 명시)
		if (reviewRepository.countByOrderIdIncludingDeleted(orderId) > 0) {
			throw new DuplicateResourceException("해당 주문에는 이미 작성한 리뷰가 존재합니다.");
		}

		Review review = Review.create(order, request.getRating(), request.getContent());

		// 5) 최종 방어선: DB UNIQUE 제약
		//    saveAndFlush()로 INSERT를 즉시 DB로 보내야
		//    (a) UNIQUE 위반을 여기서 잡아 비즈니스 예외로 변환할 수 있고
		//    (b) 아래 평점 재계산이 방금 만든 리뷰를 포함해서 집계할 수 있다.
		try {
			reviewRepository.saveAndFlush(review);
		} catch (DataIntegrityViolationException e) {
			log.warn("리뷰 중복 생성 시도 감지. orderId={}", orderId, e);
			throw new DuplicateResourceException("해당 주문에는 이미 작성한 리뷰가 존재합니다.", e);
		}

		updateRestaurantRatingWithLock(order.getRestaurant().getId());

		return ReviewResponseDto.from(review);
	}

	public ReviewResponseDto getReview(UUID reviewId) {
		Review review = reviewRepository.findById(reviewId)
			.orElseThrow(() -> new ResourceNotFoundException("해당 리뷰를 찾을 수 없습니다."));
		return ReviewResponseDto.from(review);
	}

	public Page<ReviewResponseDto> getRestaurantReviews(UUID restaurantId, ReviewSearchCondition condition,
		Pageable pageable) {
		if (!restaurantRepository.existsById(restaurantId)) {
			throw new ResourceNotFoundException("해당 가게를 찾을 수 없습니다.");
		}
		// QueryDsl 메서드
		Page<Review> reviewPage = reviewRepository.searchRestaurantReviews(restaurantId, condition, pageable);
		return reviewPage.map(ReviewResponseDto::from);
	}

	@Transactional
	public ReviewResponseDto updateReview(UUID reviewId, ReviewRequestDto request, Long customerId) {
		Review review = reviewRepository.findById(reviewId)
			.orElseThrow(() -> new ResourceNotFoundException("수정할 리뷰를 찾을 수 없습니다."));

		if (!review.getCustomer().getId().equals(customerId)) {
			throw new AccessDeniedException("자신의 리뷰만 수정할 수 있습니다.");
		}

		// Dirty Check 로 별점/내용 변경
		review.updateContentAndRating(request.getContent(), request.getRating());

		// 변경 내용을 락 획득 전에 DB로 밀어 넣는다.
		// (JPA의 auto-flush에 의존하지 않고 "집계 대상이 최신 상태"라는 것을 코드로 명시)
		reviewRepository.flush();

		updateRestaurantRatingWithLock(review.getRestaurant().getId());

		return ReviewResponseDto.from(review);
	}

	@Transactional
	public void deleteReview(UUID reviewId, Long userId, Enums.UserRole role) {
		Review review = reviewRepository.findById(reviewId)
			.orElseThrow(() -> new ResourceNotFoundException("삭제할 리뷰를 찾을 수 없습니다."));

		// 인가 규칙을 Controller가 아니라 Service에 둔다.
		// Controller의 역할 제한만으로는 "인증된 다른 사용자"가 남의 리뷰를 지우는 것을 막지 못한다.
		boolean isAdmin = role == Enums.UserRole.MANAGER || role == Enums.UserRole.MASTER;
		if (!isAdmin && !review.getCustomer().getId().equals(userId)) {
			throw new AccessDeniedException("자신의 리뷰만 삭제할 수 있습니다.");
		}

		review.markAsDeleted(userId); // 물리 삭제가 아니라 "누가 언제 지웠는지" 기록하는 소프트 딜리트
		reviewRepository.flush();     // 삭제 표시를 락 획득 전에 DB로 반영

		updateRestaurantRatingWithLock(review.getRestaurant().getId());
	}

	// 식당 참조 객체가 아니라 외래키(UUID)를 받는 이유
	// : findById()로 이미 영속성 컨텍스트에 올라온 엔티티는 select 로 읽은 것이라 DB 행 잠금이 걸려 있지 않다.
	//   락을 걸려면 "SELECT ... FOR UPDATE" 쿼리가 실제로 DB로 나가야 한다.
	private void updateRestaurantRatingWithLock(UUID restaurantId) {
		// 이 시점에 select ... for update 가 나가고, 해당 행에 대한 배타 잠금을 획득한다.
		// 잠금 -> 집계 -> 반영 순서를 지켜야 여러 리뷰가 동시에 저장돼도 평점/개수가 덮어써지지 않는다.
		Restaurant restaurant = restaurantRepository.findByIdWithPessimisticLock(restaurantId)
			.orElseThrow(() -> new ResourceNotFoundException("존재하지 않는 가게입니다."));

		Long reviewCount = reviewRepository.countByRestaurantIdAndIsDeletedFalse(restaurant.getId());
		double averageRating = reviewRepository.calculateAverageRatingByRestaurantId(restaurant.getId());

		restaurant.updateRatingAndCount(averageRating, reviewCount);
	}

}

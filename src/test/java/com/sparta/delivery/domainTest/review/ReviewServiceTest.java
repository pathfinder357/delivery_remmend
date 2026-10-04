package com.sparta.delivery.domainTest.review;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.sparta.delivery.domain.order.entity.Order;
import com.sparta.delivery.domain.order.repository.OrderRepository;
import com.sparta.delivery.domain.restaurant.entity.Restaurant;
import com.sparta.delivery.domain.restaurant.repository.RestaurantRepository;
import com.sparta.delivery.domain.review.dto.ReviewRequestDto;
import com.sparta.delivery.domain.review.dto.ReviewResponseDto;
import com.sparta.delivery.domain.review.dto.ReviewSearchCondition;
import com.sparta.delivery.domain.review.entity.Review;
import com.sparta.delivery.domain.review.repository.ReviewRepository;
import com.sparta.delivery.domain.review.service.ReviewService;
import com.sparta.delivery.domain.user.entity.User;
import com.sparta.delivery.global.common.Enums;
import com.sparta.delivery.global.exception.DuplicateResourceException;
import com.sparta.delivery.global.exception.ResourceNotFoundException;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

	@InjectMocks
	private ReviewService reviewService;

	@Mock
	private ReviewRepository reviewRepository;

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private RestaurantRepository restaurantRepository;

	@Test
	@DisplayName("리뷰 생성 성공 - 저장 후 가게 평점/개수가 비관적 락 아래에서 갱신된다")
	void createReview_Success() {
		// given
		Long customerId = 1L;
		UUID restaurantId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();

		User customer = createFakeUser(customerId);
		Restaurant restaurant = createFakeRestaurant(restaurantId);
		Order order = createFakeOrder(orderId, customer, restaurant, Enums.OrderStatus.COMPLETED);
		ReviewRequestDto requestDto = new ReviewRequestDto(5, "Good");

		given(orderRepository.findById(orderId)).willReturn(Optional.of(order));
		given(reviewRepository.countByOrderIdIncludingDeleted(orderId)).willReturn(0L);
		given(reviewRepository.saveAndFlush(any(Review.class))).willAnswer(inv -> inv.getArgument(0));
		given(restaurantRepository.findByIdWithPessimisticLock(restaurantId)).willReturn(Optional.of(restaurant));
		given(reviewRepository.countByRestaurantIdAndIsDeletedFalse(restaurantId)).willReturn(1L);
		given(reviewRepository.calculateAverageRatingByRestaurantId(restaurantId)).willReturn(5.0);

		// when
		ReviewResponseDto response = reviewService.createReview(orderId, requestDto, customerId);

		// then
		assertThat(response.getRating()).isEqualTo(5);
		assertThat(response.getContent()).isEqualTo("Good");
		// 평점 집계는 반드시 "락을 잡은 뒤"에 수행되어야 한다.
		then(restaurantRepository).should().findByIdWithPessimisticLock(restaurantId);
	}

	@Test
	@DisplayName("리뷰 생성 실패 - 주문이 존재하지 않으면 404")
	void createReview_Fail_OrderNotFound() {
		UUID orderId = UUID.randomUUID();
		ReviewRequestDto requestDto = new ReviewRequestDto(5, "Good");

		given(orderRepository.findById(orderId)).willReturn(Optional.empty());

		assertThatThrownBy(() -> reviewService.createReview(orderId, requestDto, 1L))
			.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	@DisplayName("리뷰 생성 실패 - 본인 주문이 아니면 403 (AccessDeniedException)")
	void createReview_Fail_NotOwner() {
		Long orderOwnerId = 1L;
		Long otherUserId = 2L;
		UUID restaurantId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();

		User customer = createFakeUser(orderOwnerId);
		Restaurant restaurant = createFakeRestaurant(restaurantId);
		Order order = createFakeOrder(orderId, customer, restaurant, Enums.OrderStatus.COMPLETED);
		ReviewRequestDto requestDto = new ReviewRequestDto(5, "Good");

		given(orderRepository.findById(orderId)).willReturn(Optional.of(order));

		assertThatThrownBy(() -> reviewService.createReview(orderId, requestDto, otherUserId))
			.isInstanceOf(AccessDeniedException.class)
			.hasMessage("자신의 주문에서만 리뷰를 작성할 수 있습니다.");
	}

	@Test
	@DisplayName("리뷰 생성 실패 - 완료되지 않은 주문이면 409 (IllegalStateException)")
	void createReview_Fail_NotCompletedOrder() {
		Long customerId = 1L;
		UUID restaurantId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();

		User customer = createFakeUser(customerId);
		Restaurant restaurant = createFakeRestaurant(restaurantId);
		Order order = createFakeOrder(orderId, customer, restaurant, Enums.OrderStatus.COOKING);
		ReviewRequestDto requestDto = new ReviewRequestDto(5, "Good");

		given(orderRepository.findById(orderId)).willReturn(Optional.of(order));

		assertThatThrownBy(() -> reviewService.createReview(orderId, requestDto, customerId))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("리뷰 생성 실패 - 사전 검증에서 중복이 잡히면 409 (DuplicateResourceException)")
	void createReview_Fail_DuplicateDetectedByService() {
		Long customerId = 1L;
		UUID restaurantId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();

		User customer = createFakeUser(customerId);
		Restaurant restaurant = createFakeRestaurant(restaurantId);
		Order order = createFakeOrder(orderId, customer, restaurant, Enums.OrderStatus.COMPLETED);
		ReviewRequestDto requestDto = new ReviewRequestDto(5, "Good");

		given(orderRepository.findById(orderId)).willReturn(Optional.of(order));
		given(reviewRepository.countByOrderIdIncludingDeleted(orderId)).willReturn(1L);

		assertThatThrownBy(() -> reviewService.createReview(orderId, requestDto, customerId))
			.isInstanceOf(DuplicateResourceException.class);

		then(reviewRepository).should(never()).saveAndFlush(any(Review.class));
	}

	@Test
	@DisplayName("리뷰 생성 실패 - 동시 요청이 사전 검증을 통과해도 DB UNIQUE 위반은 409로 변환된다")
	void createReview_Fail_DuplicateDetectedByDbConstraint() {
		Long customerId = 1L;
		UUID restaurantId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();

		User customer = createFakeUser(customerId);
		Restaurant restaurant = createFakeRestaurant(restaurantId);
		Order order = createFakeOrder(orderId, customer, restaurant, Enums.OrderStatus.COMPLETED);
		ReviewRequestDto requestDto = new ReviewRequestDto(5, "Good");

		given(orderRepository.findById(orderId)).willReturn(Optional.of(order));
		// 사전 검증은 통과 (동시에 들어온 두 요청이 모두 0을 읽는 상황)
		given(reviewRepository.countByOrderIdIncludingDeleted(orderId)).willReturn(0L);
		// 그러나 DB UNIQUE 제약이 최종적으로 막는다
		given(reviewRepository.saveAndFlush(any(Review.class)))
			.willThrow(new DataIntegrityViolationException("uk_reviews_order_id"));

		assertThatThrownBy(() -> reviewService.createReview(orderId, requestDto, customerId))
			.isInstanceOf(DuplicateResourceException.class)
			.hasMessage("해당 주문에는 이미 작성한 리뷰가 존재합니다.");

		// 저장이 실패했으므로 평점 재계산까지 가면 안 된다.
		then(restaurantRepository).should(never()).findByIdWithPessimisticLock(any(UUID.class));
	}

	@Test
	@DisplayName("리뷰 수정 성공 - 변경 내용이 반영되고 평점이 재계산된다")
	void updateReview_Success() {
		Long customerId = 1L;
		UUID orderId = UUID.randomUUID();
		UUID restaurantId = UUID.randomUUID();
		UUID reviewId = UUID.randomUUID();

		User customer = createFakeUser(customerId);
		Restaurant restaurant = createFakeRestaurant(restaurantId);
		Order order = createFakeOrder(orderId, customer, restaurant, Enums.OrderStatus.COMPLETED);

		Review existReview = Review.create(order, 3, "so so");
		ReflectionTestUtils.setField(existReview, "id", reviewId);

		given(reviewRepository.findById(reviewId)).willReturn(Optional.of(existReview));
		given(restaurantRepository.findByIdWithPessimisticLock(restaurantId)).willReturn(Optional.of(restaurant));
		given(reviewRepository.countByRestaurantIdAndIsDeletedFalse(restaurantId)).willReturn(1L);
		given(reviewRepository.calculateAverageRatingByRestaurantId(restaurantId)).willReturn(5.0);

		ReviewResponseDto response = reviewService.updateReview(reviewId, new ReviewRequestDto(5, "Good"), customerId);

		assertThat(response.getRating()).isEqualTo(5);
		assertThat(response.getContent()).isEqualTo("Good");
		then(reviewRepository).should().flush();
	}

	@Test
	@DisplayName("리뷰 수정 실패 - 남의 리뷰는 수정할 수 없다")
	void updateReview_Fail_NotOwner() {
		Long ownerId = 1L;
		Long otherUserId = 2L;
		UUID orderId = UUID.randomUUID();
		UUID restaurantId = UUID.randomUUID();
		UUID reviewId = UUID.randomUUID();

		User customer = createFakeUser(ownerId);
		Restaurant restaurant = createFakeRestaurant(restaurantId);
		Order order = createFakeOrder(orderId, customer, restaurant, Enums.OrderStatus.COMPLETED);
		Review existReview = Review.create(order, 3, "so so");
		ReflectionTestUtils.setField(existReview, "id", reviewId);

		given(reviewRepository.findById(reviewId)).willReturn(Optional.of(existReview));

		assertThatThrownBy(() -> reviewService.updateReview(reviewId, new ReviewRequestDto(5, "Good"), otherUserId))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("리뷰 삭제 실패 - 로그인한 다른 고객은 남의 리뷰를 삭제할 수 없다")
	void deleteReview_Fail_NotOwner() {
		Long ownerId = 1L;
		Long otherCustomerId = 2L;
		UUID orderId = UUID.randomUUID();
		UUID restaurantId = UUID.randomUUID();
		UUID reviewId = UUID.randomUUID();

		User customer = createFakeUser(ownerId);
		Restaurant restaurant = createFakeRestaurant(restaurantId);
		Order order = createFakeOrder(orderId, customer, restaurant, Enums.OrderStatus.COMPLETED);
		Review existReview = Review.create(order, 3, "so so");
		ReflectionTestUtils.setField(existReview, "id", reviewId);

		given(reviewRepository.findById(reviewId)).willReturn(Optional.of(existReview));

		assertThatThrownBy(
			() -> reviewService.deleteReview(reviewId, otherCustomerId, Enums.UserRole.CUSTOMER))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("리뷰 삭제 성공 - 관리자(MANAGER)는 타인의 리뷰도 삭제할 수 있다")
	void deleteReview_Success_ByManager() {
		Long ownerId = 1L;
		Long managerId = 99L;
		UUID orderId = UUID.randomUUID();
		UUID restaurantId = UUID.randomUUID();
		UUID reviewId = UUID.randomUUID();

		User customer = createFakeUser(ownerId);
		Restaurant restaurant = createFakeRestaurant(restaurantId);
		Order order = createFakeOrder(orderId, customer, restaurant, Enums.OrderStatus.COMPLETED);
		Review existReview = Review.create(order, 3, "so so");
		ReflectionTestUtils.setField(existReview, "id", reviewId);

		given(reviewRepository.findById(reviewId)).willReturn(Optional.of(existReview));
		given(restaurantRepository.findByIdWithPessimisticLock(restaurantId)).willReturn(Optional.of(restaurant));
		given(reviewRepository.countByRestaurantIdAndIsDeletedFalse(restaurantId)).willReturn(0L);
		given(reviewRepository.calculateAverageRatingByRestaurantId(restaurantId)).willReturn(0.0);

		reviewService.deleteReview(reviewId, managerId, Enums.UserRole.MANAGER);

		assertThat(existReview.isDeleted()).isTrue();
		then(reviewRepository).should().flush();
	}

	@Test
	@DisplayName("가게 리뷰 목록 조회 성공 - 페이징 결과가 DTO로 변환된다")
	void getRestaurantReviews_Success() {
		Long customerId = 1L;
		UUID restaurantId = UUID.randomUUID();

		User customer = createFakeUser(customerId);
		Restaurant restaurant = createFakeRestaurant(restaurantId);

		int[] ratings = {1, 3, 4, 5};
		String[] contents = {"too bad", "so so", "not bad", "good"};

		List<Review> reviews = new ArrayList<>();
		for (int i = 0; i < ratings.length; i++) {
			Order order = createFakeOrder(UUID.randomUUID(), customer, restaurant, Enums.OrderStatus.COMPLETED);
			reviews.add(Review.create(order, ratings[i], contents[i]));
		}

		ReviewSearchCondition condition = new ReviewSearchCondition(null);
		Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
		Page<Review> page = new PageImpl<>(reviews, pageable, reviews.size());

		given(restaurantRepository.existsById(restaurantId)).willReturn(true);
		given(reviewRepository.searchRestaurantReviews(eq(restaurantId), any(ReviewSearchCondition.class), eq(pageable)))
			.willReturn(page);

		Page<ReviewResponseDto> result = reviewService.getRestaurantReviews(restaurantId, condition, pageable);

		assertThat(result.getContent()).hasSize(4);
		assertThat(result.getContent().get(0))
			.returns("too bad", ReviewResponseDto::getContent)
			.returns(1, ReviewResponseDto::getRating);
		assertThat(result.getContent().get(2))
			.returns("not bad", ReviewResponseDto::getContent)
			.returns(4, ReviewResponseDto::getRating);
	}

	@Test
	@DisplayName("가게 리뷰 목록 조회 실패 - 존재하지 않는 가게면 404")
	void getRestaurantReviews_Fail_RestaurantNotFound() {
		UUID restaurantId = UUID.randomUUID();
		Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));

		given(restaurantRepository.existsById(restaurantId)).willReturn(false);

		assertThatThrownBy(
			() -> reviewService.getRestaurantReviews(restaurantId, new ReviewSearchCondition(null), pageable))
			.isInstanceOf(ResourceNotFoundException.class);
	}

	// --- 테스트 픽스처 ---

	private User createFakeUser(Long userId) {
		User user = User.builder()
			.username("test")
			.build();
		ReflectionTestUtils.setField(user, "id", userId);
		return user;
	}

	private Restaurant createFakeRestaurant(UUID restaurantId) {
		Restaurant restaurant = Restaurant.builder()
			.name("testRestaurant")
			.build();
		ReflectionTestUtils.setField(restaurant, "id", restaurantId);
		return restaurant;
	}

	private Order createFakeOrder(UUID orderId, User user, Restaurant restaurant, Enums.OrderStatus status) {
		Order order = Order.create(user, restaurant, "test-01", "강남", "101", "123");
		ReflectionTestUtils.setField(order, "id", orderId);
		ReflectionTestUtils.setField(order, "orderStatus", status);
		return order;
	}
}

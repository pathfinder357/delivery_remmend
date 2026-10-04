package com.sparta.delivery.domain.review.controller;

import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sparta.delivery.domain.review.dto.ReviewRequestDto;
import com.sparta.delivery.domain.review.dto.ReviewResponseDto;
import com.sparta.delivery.domain.review.dto.ReviewSearchCondition;
import com.sparta.delivery.domain.review.entity.Review;
import com.sparta.delivery.domain.review.repository.ReviewRepository;
import com.sparta.delivery.domain.review.service.ReviewService;
import com.sparta.delivery.global.common.Enums;
import com.sparta.delivery.global.config.security.UserDetailsImpl;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ReviewController {
	private final ReviewService reviewService;

	// 리뷰 등록
	@PostMapping("/orders/{orderId}/reviews")
	public ResponseEntity<ReviewResponseDto> createReview(@PathVariable("orderId") UUID orderId,
		@RequestBody ReviewRequestDto request,
		@AuthenticationPrincipal UserDetailsImpl userDetails) {

		Long customerId = userDetails.getUser().getId();
		ReviewResponseDto response = reviewService.createReview(orderId, request, customerId);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	// 리뷰 단건 조회
	@GetMapping("/reviews/{reviewId}")
	public ResponseEntity<ReviewResponseDto> getReview(@PathVariable("reviewId") UUID reviewId) {
		ReviewResponseDto response = reviewService.getReview(reviewId);
		return ResponseEntity.ok(response);
	}

	// 가게 별 리뷰 목록 조회  (GET /api/restaurants/{restaurantId}/reviews)
	@GetMapping("/restaurants/{restaurantId}/reviews")
	public ResponseEntity<Page<ReviewResponseDto>> getReviews(@PathVariable("restaurantId") UUID restaurantId,
		@ModelAttribute ReviewSearchCondition condition,
		@PageableDefault(page = 0 ,size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		int size = pageable.getPageSize();
		if (size !=10 && size !=30 && size !=50) {
			size = 10;
			pageable = PageRequest.of(pageable.getPageNumber(), size, pageable.getSort());
		}
		Page<ReviewResponseDto> response = reviewService.getRestaurantReviews(restaurantId, condition, pageable);
		return ResponseEntity.ok(response);
	}

	// 리뷰 수정
	@PatchMapping("/reviews/{reviewId}")
	public ResponseEntity<ReviewResponseDto> updateReview(@PathVariable("reviewId") UUID reviewId,
		@RequestBody ReviewRequestDto request, @AuthenticationPrincipal UserDetailsImpl userDetails) {

		Long customerId = userDetails.getUser().getId();
		ReviewResponseDto response = reviewService.updateReview(reviewId, request, customerId);
		return ResponseEntity.ok(response);
	}

	// 리뷰 삭제
	@DeleteMapping("/reviews/{reviewId}")
	public ResponseEntity<Void> deleteReview(@PathVariable("reviewId") UUID reviewId,
		@AuthenticationPrincipal UserDetailsImpl userDetails) {
		Long userId = userDetails.getUser().getId();
		Enums.UserRole role = userDetails.getUser().getRole();

		// 역할/소유권 판단은 Service에서 한다.
		// (Controller에서 OWNER만 걸러내면 "로그인한 다른 고객"이 남의 리뷰를 지우는 경로가 열려 있다)
		reviewService.deleteReview(reviewId, userId, role);
		return ResponseEntity.noContent().build();
	}

}

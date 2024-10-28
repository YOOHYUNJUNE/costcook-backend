package com.costcook.domain.request;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CreateReviewRequest {
	private Long recipeId;
	private int score;
	private String comment;
}

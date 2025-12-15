package com.ebbinghaus.review.data

data class ReviewItemMinimal(
    val stage: Int,
    val nextReviewTime: Long,
    val isFinished: Boolean
)

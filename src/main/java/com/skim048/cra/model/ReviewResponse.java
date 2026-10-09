package com.skim048.cra.model;

public class ReviewResponse {

  private String prUrl;
  private String review;

  public ReviewResponse(String prUrl, String review) {
    this.prUrl = prUrl;
    this.review = review;
  }

  public String getPrUrl() {
    return prUrl;
  }

  public String getReview() {
    return review;
  }
}

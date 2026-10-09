package com.skim048.cra.controller;

import com.skim048.cra.model.ReviewRequest;
import com.skim048.cra.model.ReviewResponse;
import com.skim048.cra.service.ClaudeService;
import com.skim048.cra.service.GitHubService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/review")
public class ReviewController {

  private final GitHubService gitHubService;
  private final ClaudeService claudeService;

  public ReviewController(GitHubService gitHubService, ClaudeService claudeService) {
    this.gitHubService = gitHubService;
    this.claudeService = claudeService;
  }

  @PostMapping
  public ResponseEntity<ReviewResponse> review(@RequestBody ReviewRequest request) throws IOException {
    String diff = gitHubService.fetchPullRequestDiff(request.getPrUrl());
    String reviewText = claudeService.review(diff);
    return ResponseEntity.ok(new ReviewResponse(request.getPrUrl(), reviewText));
  }
}

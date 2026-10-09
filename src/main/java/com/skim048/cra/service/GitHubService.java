package com.skim048.cra.service;

import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHPullRequestFileDetail;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GitHubService {

  private static final Pattern PR_URL_PATTERN =
    Pattern.compile("https://github\\.com/([^/]+/[^/]+)/pull/(\\d+)");

  @Value("${github.token}")
  private String token;

  public String fetchPullRequestDiff(String prUrl) throws IOException {
    Matcher matcher = PR_URL_PATTERN.matcher(prUrl);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid GitHub PR URL: " + prUrl);
    }

    String repoName = matcher.group(1);
    int prNumber = Integer.parseInt(matcher.group(2));

    GitHub github = new GitHubBuilder().withOAuthToken(token).build();
    GHRepository repo = github.getRepository(repoName);
    GHPullRequest pr = repo.getPullRequest(prNumber);

    List<GHPullRequestFileDetail> files = pr.listFiles().toList();

    StringBuilder diff = new StringBuilder();
    diff.append("PR #").append(prNumber).append(": ").append(pr.getTitle()).append("\n\n");

    for (GHPullRequestFileDetail file : files) {
      diff.append("--- ").append(file.getFilename()).append("\n");
      diff.append("Status: ").append(file.getStatus()).append("\n");
      if (file.getPatch() != null) {
        diff.append(file.getPatch()).append("\n");
      }
      diff.append("\n");
    }

    return diff.toString();
  }
}

package com.skim048.cra.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class ClaudeService {

  @Value("${claude.api.key}")
  private String apiKey;

  @Value("${claude.api.model}")
  private String model;

  @Value("${claude.api.url}")
  private String apiUrl;

  private final WebClient webClient;

  public ClaudeService(WebClient.Builder webClientBuilder) {
    this.webClient = webClientBuilder.build();
  }

  public String review(String diff) {
    String prompt = """
      You are an expert code reviewer. Review the following pull request diff and provide:
      1. A summary of what the change does
      2. Potential bugs or correctness issues
      3. Code quality and style observations
      4. Any security concerns
      5. Overall verdict: Approve / Request Changes / Comment

      Be concise and actionable. Focus on the most important issues.

      Diff:
      """ + diff;

    Map<String, Object> requestBody = Map.of(
      "model", model,
      "max_tokens", 2048,
      "messages", List.of(
        Map.of("role", "user", "content", prompt)
      )
    );

    Map response = webClient.post()
      .uri(apiUrl)
      .header("x-api-key", apiKey)
      .header("anthropic-version", "2023-06-01")
      .header("Content-Type", "application/json")
      .bodyValue(requestBody)
      .retrieve()
      .bodyToMono(Map.class)
      .block();

    List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
    return (String) content.get(0).get("text");
  }
}

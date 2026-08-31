package io.github.lalathomas.walletledger.wallet.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WalletApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM wallets");
    }

    @Test
    void supportsCompleteWalletApiFlowAndIdempotentReplay() throws Exception {
        UUID playerId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("playerId", playerId))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith(
                        "/api/v1/wallets/" + playerId + "/balance"
                )))
                .andExpect(jsonPath("$.playerId").value(playerId.toString()))
                .andExpect(jsonPath("$.balance").value(0));

        String creditBody = movementBody(100, "Mission reward", "mission-42");
        MvcResult firstCredit = mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "api-credit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creditBody))
                .andExpect(status().isOk())
                .andExpect(header().string(WalletController.IDEMPOTENT_REPLAYED_HEADER, "false"))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.balanceAfter").value(100))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        JsonNode firstCreditJson = objectMapper.readTree(firstCredit.getResponse().getContentAsString());

        mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "api-credit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(creditBody))
                .andExpect(status().isOk())
                .andExpect(header().string(WalletController.IDEMPOTENT_REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.transactionId").value(
                        firstCreditJson.get("transactionId").asText()
                ))
                .andExpect(jsonPath("$.balanceAfter").value(100))
                .andExpect(jsonPath("$.replayed").value(true));

        mockMvc.perform(post("/api/v1/wallets/{playerId}/debits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "api-debit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(30, "Shop purchase", "purchase-9")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("DEBIT"))
                .andExpect(jsonPath("$.balanceAfter").value(70));

        mockMvc.perform(get("/api/v1/wallets/{playerId}/balance", playerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playerId").value(playerId.toString()))
                .andExpect(jsonPath("$.balance").value(70));

        mockMvc.perform(get("/api/v1/wallets/{playerId}/transactions", playerId)
                        .queryParam("page", "0")
                        .queryParam("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.length()").value(1))
                .andExpect(jsonPath("$.transactions[0].type").value("DEBIT"))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.page.first").value(true))
                .andExpect(jsonPath("$.page.last").value(false));
    }

    @Test
    void returnsStructuredErrorsForValidationAndMalformedRequests() throws Exception {
        UUID playerId = createWallet();

        mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "negative-amount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(-1, "Invalid", "invalid-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.amount").value("amount must be greater than zero"));

        mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "decimal-amount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 1.9,
                                  "reason": "Must not be truncated",
                                  "referenceId": "decimal-1"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        mockMvc.perform(get("/api/v1/wallets/{playerId}/balance", playerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0));
        mockMvc.perform(get("/api/v1/wallets/{playerId}/transactions", playerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(1, "Reward", "reward-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_REQUEST_VALUE"));

        mockMvc.perform(get("/api/v1/wallets/not-a-uuid/balance"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

        mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "malformed-json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void mapsDomainFailuresToClearHttpResponsesWithoutPartialChanges() throws Exception {
        UUID playerId = createWallet();
        mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "small-credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(5, "Small reward", "reward-small")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/wallets/{playerId}/debits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "too-large-debit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(6, "Purchase", "purchase-large")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.details.requestedAmount").value(6))
                .andExpect(jsonPath("$.details.availableBalance").value(5));

        mockMvc.perform(get("/api/v1/wallets/{playerId}/balance", playerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(5));

        mockMvc.perform(get("/api/v1/wallets/{playerId}/balance", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentRequest() throws Exception {
        UUID playerId = createWallet();

        mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "conflicting-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(10, "Reward", "reward-1")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "conflicting-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(11, "Reward", "reward-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        mockMvc.perform(get("/api/v1/wallets/{playerId}/balance", playerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(10));
    }

    @Test
    void supportsRefundApiReplayLinkageAndReconciliation() throws Exception {
        UUID playerId = createWallet();
        mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "refund-api-credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(100, "Initial balance", "refund-api-initial")))
                .andExpect(status().isOk());

        MvcResult debitResult = mockMvc.perform(
                        post("/api/v1/wallets/{playerId}/debits", playerId)
                                .header(WalletController.IDEMPOTENCY_KEY_HEADER, "refund-api-debit")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(movementBody(35, "Purchase", "refund-api-purchase")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(65))
                .andReturn();
        String debitId = objectMapper.readTree(
                debitResult.getResponse().getContentAsString()
        ).get("transactionId").asText();

        mockMvc.perform(post(
                        "/api/v1/wallets/{playerId}/transactions/{debitId}/refunds",
                        playerId,
                        debitId
                )
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "refund-api-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 1,
                                  "reason": "Approved refund",
                                  "referenceId": "refund-api-case"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(get("/api/v1/wallets/{playerId}/balance", playerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(65));
        mockMvc.perform(get("/api/v1/wallets/{playerId}/transactions", playerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(2));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE refunded_debit_id IS NOT NULL",
                Long.class
        )).isZero();

        String refundBody = refundBody("Approved refund", "refund-api-case");
        MvcResult refundResult = mockMvc.perform(post(
                        "/api/v1/wallets/{playerId}/transactions/{debitId}/refunds",
                        playerId,
                        debitId
                )
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "refund-api-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody))
                .andExpect(status().isOk())
                .andExpect(header().string(WalletController.IDEMPOTENT_REPLAYED_HEADER, "false"))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.amount").value(35))
                .andExpect(jsonPath("$.balanceAfter").value(100))
                .andExpect(jsonPath("$.refundedDebitId").value(debitId))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        String refundId = objectMapper.readTree(
                refundResult.getResponse().getContentAsString()
        ).get("transactionId").asText();

        mockMvc.perform(post(
                        "/api/v1/wallets/{playerId}/transactions/{debitId}/refunds",
                        playerId,
                        debitId
                )
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "refund-api-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody))
                .andExpect(status().isOk())
                .andExpect(header().string(WalletController.IDEMPOTENT_REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.transactionId").value(refundId))
                .andExpect(jsonPath("$.refundedDebitId").value(debitId))
                .andExpect(jsonPath("$.replayed").value(true));

        mockMvc.perform(post(
                        "/api/v1/wallets/{playerId}/transactions/{debitId}/refunds",
                        playerId,
                        debitId
                )
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "another-refund-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEBIT_ALREADY_REFUNDED"));

        mockMvc.perform(get("/api/v1/wallets/{playerId}/reconciliation", playerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storedBalance").value(100))
                .andExpect(jsonPath("$.calculatedBalance").value(100))
                .andExpect(jsonPath("$.consistent").value(true))
                .andExpect(jsonPath("$.transactionCount").value(3))
                .andExpect(jsonPath("$.checkedAt").isNotEmpty());

        MvcResult historyResult = mockMvc.perform(
                        get("/api/v1/wallets/{playerId}/transactions", playerId)
                                .queryParam("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andReturn();
        JsonNode transactions = objectMapper.readTree(
                historyResult.getResponse().getContentAsString()
        ).get("transactions");
        boolean linkedRefundPresent = false;
        for (JsonNode transaction : transactions) {
            if (refundId.equals(transaction.get("transactionId").asText())) {
                linkedRefundPresent = debitId.equals(transaction.get("refundedDebitId").asText());
            }
        }
        org.assertj.core.api.Assertions.assertThat(linkedRefundPresent).isTrue();
    }

    @Test
    void mapsRefundTargetFailuresWithoutExposingAnotherWallet() throws Exception {
        UUID ownerId = createWallet();
        MvcResult creditResult = mockMvc.perform(
                        post("/api/v1/wallets/{playerId}/credits", ownerId)
                                .header(WalletController.IDEMPOTENCY_KEY_HEADER, "target-api-credit")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(movementBody(20, "Initial", "target-api-initial")))
                .andExpect(status().isOk())
                .andReturn();
        String creditId = objectMapper.readTree(
                creditResult.getResponse().getContentAsString()
        ).get("transactionId").asText();
        MvcResult debitResult = mockMvc.perform(
                        post("/api/v1/wallets/{playerId}/debits", ownerId)
                                .header(WalletController.IDEMPOTENCY_KEY_HEADER, "target-api-debit")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(movementBody(5, "Purchase", "target-api-purchase")))
                .andExpect(status().isOk())
                .andReturn();
        String debitId = objectMapper.readTree(
                debitResult.getResponse().getContentAsString()
        ).get("transactionId").asText();
        UUID otherPlayerId = createWallet();

        mockMvc.perform(post(
                        "/api/v1/wallets/{playerId}/transactions/{transactionId}/refunds",
                        ownerId,
                        creditId
                )
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "credit-refund-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody("Invalid target", "credit-target")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_REFUNDABLE"));

        mockMvc.perform(post(
                        "/api/v1/wallets/{playerId}/transactions/{transactionId}/refunds",
                        otherPlayerId,
                        debitId
                )
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "wrong-wallet-attempt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody("Wrong wallet", "wrong-wallet")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));

        mockMvc.perform(post(
                        "/api/v1/wallets/{playerId}/transactions/{transactionId}/refunds",
                        ownerId,
                        UUID.randomUUID()
                )
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "missing-refund-target")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody("Missing", "missing-target")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void propagatesSafeCorrelationIdentifiersOnSuccessAndErrors() throws Exception {
        UUID playerId = createWallet();
        String supplied = "client.trace-123";

        mockMvc.perform(get("/api/v1/wallets/{playerId}/balance", playerId)
                        .header("X-Correlation-ID", supplied))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-ID", supplied));

        mockMvc.perform(get("/api/v1/wallets/{playerId}/balance", UUID.randomUUID())
                        .header("X-Correlation-ID", supplied))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-ID", supplied))
                .andExpect(jsonPath("$.correlationId").value(supplied));

        MvcResult replaced = mockMvc.perform(
                        get("/api/v1/wallets/{playerId}/balance", playerId)
                                .header("X-Correlation-ID", "unsafe correlation id"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "X-Correlation-ID",
                        org.hamcrest.Matchers.matchesPattern(
                                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                        )
                ))
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(
                replaced.getResponse().getHeader("X-Correlation-ID")
        ).isNotEqualTo("unsafe correlation id");

        String frameworkCorrelation = "framework.trace-456";
        mockMvc.perform(get("/api/v1/not-a-route")
                        .header("X-Correlation-ID", frameworkCorrelation))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-ID", frameworkCorrelation))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.code").value("HTTP_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(
                        "No endpoint exists for the requested path"
                ))
                .andExpect(jsonPath("$.path").value("/api/v1/not-a-route"))
                .andExpect(jsonPath("$.correlationId").value(frameworkCorrelation))
                .andExpect(jsonPath("$.fieldErrors").isMap())
                .andExpect(jsonPath("$.details").isMap());

        mockMvc.perform(get("/api/v1/wallets")
                        .header("X-Correlation-ID", frameworkCorrelation))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(
                        org.springframework.http.HttpHeaders.ALLOW,
                        org.hamcrest.Matchers.containsString("POST")
                ))
                .andExpect(header().string("X-Correlation-ID", frameworkCorrelation))
                .andExpect(jsonPath("$.code").value("HTTP_METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.path").value("/api/v1/wallets"))
                .andExpect(jsonPath("$.correlationId").value(frameworkCorrelation));

        mockMvc.perform(post("/api/v1/wallets")
                        .header("X-Correlation-ID", frameworkCorrelation)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string(
                        org.springframework.http.HttpHeaders.ACCEPT,
                        org.hamcrest.Matchers.containsString(MediaType.APPLICATION_JSON_VALUE)
                ))
                .andExpect(header().string("X-Correlation-ID", frameworkCorrelation))
                .andExpect(jsonPath("$.code").value("HTTP_UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.path").value("/api/v1/wallets"))
                .andExpect(jsonPath("$.correlationId").value(frameworkCorrelation));
    }

    @Test
    void exposesOpenApiAndOnlyTheConfiguredOperationalEndpoints() throws Exception {
        MvcResult apiDocsResult = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Wallet Ledger API"))
                .andReturn();
        JsonNode apiDocs = objectMapper.readTree(
                apiDocsResult.getResponse().getContentAsString()
        );
        org.assertj.core.api.Assertions.assertThat(apiDocs.path("openapi").asText())
                .startsWith("3.1.");

        JsonNode createResponses = apiDocs.path("paths")
                .path("/api/v1/wallets")
                .path("post")
                .path("responses");
        org.assertj.core.api.Assertions.assertThat(createResponses.has("201")).isTrue();
        org.assertj.core.api.Assertions.assertThat(createResponses.has("200")).isFalse();
        org.assertj.core.api.Assertions.assertThat(
                createResponses.path("201").path("headers").has("Location")
        ).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                createResponses.path("201").path("headers").has("X-Correlation-ID")
        ).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                createResponses.path("201")
                        .path("content")
                        .path(MediaType.APPLICATION_JSON_VALUE)
                        .path("schema")
                        .path("$ref")
                        .asText()
        ).isEqualTo("#/components/schemas/WalletResponse");

        for (String movementPath : java.util.List.of(
                "/api/v1/wallets/{playerId}/credits",
                "/api/v1/wallets/{playerId}/debits"
        )) {
            JsonNode successResponse = apiDocs.path("paths")
                    .path(movementPath)
                    .path("post")
                    .path("responses")
                    .path("200");
            org.assertj.core.api.Assertions.assertThat(
                    successResponse.path("headers").has("X-Correlation-ID")
            ).isTrue();
            org.assertj.core.api.Assertions.assertThat(
                    successResponse.path("headers")
                            .path(WalletController.IDEMPOTENT_REPLAYED_HEADER)
                            .path("schema")
                            .path("type")
                            .asText()
            ).isEqualTo("boolean");
            org.assertj.core.api.Assertions.assertThat(
                    successResponse.path("content")
                            .path(MediaType.APPLICATION_JSON_VALUE)
                            .path("schema")
                            .path("$ref")
                            .asText()
            ).isEqualTo("#/components/schemas/MoneyMovementResponse");
        }

        String refundPath = "/api/v1/wallets/{playerId}/transactions/"
                + "{debitTransactionId}/refunds";
        JsonNode refundOperation = apiDocs.path("paths")
                .path(refundPath)
                .path("post");
        org.assertj.core.api.Assertions.assertThat(refundOperation.isMissingNode()).isFalse();

        JsonNode refundParameters = refundOperation.path("parameters");
        org.assertj.core.api.Assertions.assertThat(refundParameters)
                .anySatisfy(parameter -> {
                    org.assertj.core.api.Assertions.assertThat(
                            parameter.path("name").asText()
                    ).isEqualTo("X-Correlation-ID");
                    org.assertj.core.api.Assertions.assertThat(
                            parameter.path("in").asText()
                    ).isEqualTo("header");
                    org.assertj.core.api.Assertions.assertThat(
                            parameter.path("required").asBoolean()
                    ).isFalse();
                    org.assertj.core.api.Assertions.assertThat(
                            parameter.path("schema").path("maxLength").asInt()
                    ).isEqualTo(100);
                });
        org.assertj.core.api.Assertions.assertThat(refundParameters)
                .anySatisfy(parameter -> {
                    org.assertj.core.api.Assertions.assertThat(
                            parameter.path("name").asText()
                    ).isEqualTo("Idempotency-Key");
                    org.assertj.core.api.Assertions.assertThat(
                            parameter.path("in").asText()
                    ).isEqualTo("header");
                    org.assertj.core.api.Assertions.assertThat(
                            parameter.path("required").asBoolean()
                    ).isTrue();
                });
        JsonNode refundResponses = refundOperation.path("responses");
        for (String responseCode : java.util.List.of(
                "200", "400", "404", "409", "422", "503"
        )) {
            org.assertj.core.api.Assertions.assertThat(refundResponses.has(responseCode)).isTrue();
            org.assertj.core.api.Assertions.assertThat(
                    refundResponses.path(responseCode)
                            .path("headers")
                            .has("X-Correlation-ID")
            ).isTrue();
        }
        org.assertj.core.api.Assertions.assertThat(
                refundResponses.path("200")
                        .path("headers")
                        .has(WalletController.IDEMPOTENT_REPLAYED_HEADER)
        ).isTrue();
        org.assertj.core.api.Assertions.assertThat(
                refundResponses.path("200")
                        .path("headers")
                        .path(WalletController.IDEMPOTENT_REPLAYED_HEADER)
                        .path("schema")
                        .path("type")
                        .asText()
        ).isEqualTo("boolean");
        org.assertj.core.api.Assertions.assertThat(
                refundResponses.path("200")
                        .path("content")
                        .path(MediaType.APPLICATION_JSON_VALUE)
                        .path("schema")
                        .path("$ref")
                        .asText()
        ).isEqualTo("#/components/schemas/MoneyMovementResponse");
        for (String responseCode : java.util.List.of("400", "404", "409", "422", "503")) {
            org.assertj.core.api.Assertions.assertThat(
                    refundResponses.path(responseCode)
                            .path("content")
                            .path(MediaType.APPLICATION_JSON_VALUE)
                            .path("schema")
                            .path("$ref")
                            .asText()
            ).isEqualTo("#/components/schemas/ApiErrorResponse");
        }
        org.assertj.core.api.Assertions.assertThat(
                refundResponses.path("503")
                        .path("headers")
                        .has(org.springframework.http.HttpHeaders.RETRY_AFTER)
        ).isTrue();

        for (String schemaName : java.util.List.of(
                "MoneyMovementResponse", "TransactionResponse"
        )) {
            JsonNode refundLink = apiDocs.path("components")
                    .path("schemas")
                    .path(schemaName)
                    .path("properties")
                    .path("refundedDebitId");
            JsonNode typeNode = refundLink.path("type");
            org.assertj.core.api.Assertions.assertThat(typeNode.isArray()).isTrue();
            java.util.Set<String> types = new java.util.HashSet<>();
            typeNode.forEach(node -> types.add(node.asText()));
            org.assertj.core.api.Assertions.assertThat(types)
                    .containsExactlyInAnyOrder("string", "null");
            org.assertj.core.api.Assertions.assertThat(refundLink.path("format").asText())
                    .isEqualTo("uuid");
        }

        String reconciliationPath = "/api/v1/wallets/{playerId}/reconciliation";
        JsonNode reconciliationSchema = apiDocs.path("paths")
                .path(reconciliationPath)
                .path("get")
                .path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("schema");
        org.assertj.core.api.Assertions.assertThat(
                reconciliationSchema.path("$ref").asText()
        ).isEqualTo("#/components/schemas/ReconciliationResponse");
        JsonNode reconciliationProperties = apiDocs.path("components")
                .path("schemas")
                .path("ReconciliationResponse")
                .path("properties");
        org.assertj.core.api.Assertions.assertThat(reconciliationProperties.has("storedBalance"))
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(reconciliationProperties.has("calculatedBalance"))
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(reconciliationProperties.has("consistent"))
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(reconciliationProperties.has("transactionCount"))
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(reconciliationProperties.has("checkedAt"))
                .isTrue();

        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", endsWith("/swagger-ui/index.html")));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.readinessState.status").value("UP"))
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.diskSpace").doesNotExist());
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app.name").value("wallet-ledger-service"));
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")));

        mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/configprops")).andExpect(status().isNotFound());
    }

    private UUID createWallet() throws Exception {
        UUID playerId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("playerId", playerId))))
                .andExpect(status().isCreated());
        return playerId;
    }

    private String refundBody(String reason, String referenceId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "reason", reason,
                "referenceId", referenceId
        ));
    }

    private String movementBody(long amount, String reason, String referenceId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "amount", amount,
                "reason", reason,
                "referenceId", referenceId
        ));
    }
}

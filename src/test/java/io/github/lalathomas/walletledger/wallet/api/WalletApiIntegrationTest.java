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
    void returnsConsistentErrorsForStandardHttpFailures() throws Exception {
        UUID playerId = createWallet();

        mockMvc.perform(get("/api/v1/unknown-resource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/v1/unknown-resource"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/wallets/{playerId}/balance", playerId))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not-json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        mockMvc.perform(get("/api/v1/wallets/{playerId}/balance", playerId)
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void rejectsInvalidPaginationAndIdempotencyKeyBoundaries() throws Exception {
        UUID playerId = createWallet();

        mockMvc.perform(get("/api/v1/wallets/{playerId}/transactions", playerId)
                        .queryParam("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/v1/wallets/{playerId}/transactions", playerId)
                        .queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "invalid key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(1, "Reward", "reward-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "x".repeat(101))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(1, "Reward", "reward-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void mapsDuplicateWalletAndBalanceOverflowWithoutPartialChanges() throws Exception {
        UUID playerId = createWallet();
        String createBody = objectMapper.writeValueAsString(Map.of("playerId", playerId));

        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WALLET_ALREADY_EXISTS"));

        mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "maximum-credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(Long.MAX_VALUE, "Maximum", "maximum-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(Long.MAX_VALUE));

        mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "overflow-credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(1, "Overflow", "overflow-1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BALANCE_OVERFLOW"));

        mockMvc.perform(get("/api/v1/wallets/{playerId}/balance", playerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(Long.MAX_VALUE));
    }

    @Test
    void refundsDebitWithAuditLinkAndEnforcesRefundRules() throws Exception {
        UUID playerId = createWallet();
        MvcResult creditResult = mockMvc.perform(post("/api/v1/wallets/{playerId}/credits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "refund-flow-credit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(100, "Initial balance", "refund-flow-initial")))
                .andExpect(status().isOk())
                .andReturn();
        String creditTransactionId = objectMapper.readTree(
                creditResult.getResponse().getContentAsString()
        ).get("transactionId").asText();

        MvcResult debitResult = mockMvc.perform(post("/api/v1/wallets/{playerId}/debits", playerId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "refund-flow-debit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(35, "Shop purchase", "refund-flow-purchase")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(65))
                .andReturn();
        String debitTransactionId = objectMapper.readTree(
                debitResult.getResponse().getContentAsString()
        ).get("transactionId").asText();
        String refundBody = objectMapper.writeValueAsString(Map.of(
                "reason", "Purchase cancelled",
                "referenceId", "support-ticket-35"
        ));
        String refundPath = "/api/v1/wallets/{playerId}/transactions/{transactionId}/refund";

        MvcResult refundResult = mockMvc.perform(post(refundPath, playerId, debitTransactionId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "refund-flow-refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody))
                .andExpect(status().isOk())
                .andExpect(header().string(WalletController.IDEMPOTENT_REPLAYED_HEADER, "false"))
                .andExpect(jsonPath("$.type").value("REFUND"))
                .andExpect(jsonPath("$.amount").value(35))
                .andExpect(jsonPath("$.balanceAfter").value(100))
                .andExpect(jsonPath("$.reversalOfTransactionId").value(debitTransactionId))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        String refundTransactionId = objectMapper.readTree(
                refundResult.getResponse().getContentAsString()
        ).get("transactionId").asText();

        mockMvc.perform(post(refundPath, playerId, debitTransactionId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "refund-flow-refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody))
                .andExpect(status().isOk())
                .andExpect(header().string(WalletController.IDEMPOTENT_REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.transactionId").value(refundTransactionId))
                .andExpect(jsonPath("$.replayed").value(true));

        mockMvc.perform(post(refundPath, playerId, debitTransactionId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "second-refund-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_REFUNDED"))
                .andExpect(jsonPath("$.details.refundTransactionId").value(refundTransactionId));

        mockMvc.perform(post(refundPath, playerId, creditTransactionId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "refund-credit-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(refundBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_REFUNDABLE"));

        mockMvc.perform(post(refundPath, playerId, debitTransactionId)
                        .header(WalletController.IDEMPOTENCY_KEY_HEADER, "invalid-refund-body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/wallets/{playerId}/balance", playerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100));
    }

    private UUID createWallet() throws Exception {
        UUID playerId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("playerId", playerId))))
                .andExpect(status().isCreated());
        return playerId;
    }

    private String movementBody(long amount, String reason, String referenceId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "amount", amount,
                "reason", reason,
                "referenceId", referenceId
        ));
    }
}

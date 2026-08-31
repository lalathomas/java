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

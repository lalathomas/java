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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OptionalWalletFeaturesApiIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM fund_reservations");
        jdbcTemplate.update("DELETE FROM wallets");
    }

    @Test
    void exposesTransferReservationAndReconciliationApis() throws Exception {
        UUID source = createWallet();
        UUID destination = createWallet();
        postMovement(source, "credits", "feature-credit", 100, "Initial", "initial");

        String transferBody = objectMapper.writeValueAsString(Map.of(
                "destinationPlayerId", destination,
                "amount", 25,
                "reason", "Player gift",
                "referenceId", "gift-1"
        ));
        mockMvc.perform(post("/api/v1/wallets/{playerId}/transfers", source)
                        .header("Idempotency-Key", "feature-transfer")
                        .contentType(MediaType.APPLICATION_JSON).content(transferBody))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.sourceBalanceAfter").value(75))
                .andExpect(jsonPath("$.destinationBalanceAfter").value(25));

        MvcResult hold = mockMvc.perform(post("/api/v1/wallets/{playerId}/reservations", source)
                        .header("Idempotency-Key", "feature-reserve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(30, "Auction bid", "bid-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.availableBalance").value(45))
                .andReturn();
        JsonNode holdJson = objectMapper.readTree(hold.getResponse().getContentAsString());
        String reservationId = holdJson.get("reservationId").asText();

        String actionBody = objectMapper.writeValueAsString(Map.of(
                "reason", "Bid cancelled", "referenceId", "bid-1-release"
        ));
        mockMvc.perform(post(
                            "/api/v1/wallets/{playerId}/reservations/{reservationId}/release",
                            source, reservationId
                        )
                        .header("Idempotency-Key", "feature-release")
                        .contentType(MediaType.APPLICATION_JSON).content(actionBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.availableBalance").value(75));

        mockMvc.perform(get("/api/v1/wallets/{playerId}/reconciliation", source))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storedBalance").value(75))
                .andExpect(jsonPath("$.calculatedBalance").value(75))
                .andExpect(jsonPath("$.storedReservedBalance").value(0))
                .andExpect(jsonPath("$.consistent").value(true));
    }

    private UUID createWallet() throws Exception {
        UUID playerId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("playerId", playerId))))
                .andExpect(status().isCreated());
        return playerId;
    }

    private void postMovement(
            UUID playerId, String operation, String key,
            long amount, String reason, String reference
    ) throws Exception {
        mockMvc.perform(post("/api/v1/wallets/{playerId}/{operation}", playerId, operation)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(movementBody(amount, reason, reference)))
                .andExpect(status().isOk());
    }

    private String movementBody(long amount, String reason, String reference) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "amount", amount, "reason", reason, "referenceId", reference
        ));
    }
}

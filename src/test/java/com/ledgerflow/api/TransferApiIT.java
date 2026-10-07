package com.ledgerflow.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.security.JwtService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end HTTP: auth, validation, idempotency headers, error shapes.
 */
@AutoConfigureMockMvc
class TransferApiIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JwtService jwt;

    private String operatorToken() {
        return jwt.issueToken("operator", List.of("OPERATOR"));
    }

    private UUID createAccount(String owner, long balance) throws Exception {
        String body = mvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerName":"%s","currency":"USD","initialBalanceMinorUnits":%d}
                                """.formatted(owner, balance)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumber").exists())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(body).get("id").asText());
    }

    @Test
    void fullTransferFlowOverHttp() throws Exception {
        UUID a = createAccount("api-alice", 10_000);
        UUID b = createAccount("api-bob", 0);
        String key = UUID.randomUUID().toString();
        String transferBody = """
                {"sourceAccountId":"%s","destAccountId":"%s",
                 "amountMinorUnits":2500,"currency":"USD","description":"api test"}
                """.formatted(a, b);

        // First request: 201, not a replay.
        String first = mvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + operatorToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.transactionId").exists())
                .andReturn().getResponse().getContentAsString();
        JsonNode firstJson = mapper.readTree(first);

        // Retry: 200 with the replay marker, same transaction.
        mvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + operatorToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.transactionId").value(firstJson.get("transactionId").asText()));

        // Balances reflect exactly one transfer.
        mvc.perform(get("/api/v1/accounts/" + a + "/balances")
                        .header("Authorization", "Bearer " + operatorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cachedBalanceMinorUnits").value(7500))
                .andExpect(jsonPath("$.authoritativeBalanceMinorUnits").value(7500))
                .andExpect(jsonPath("$.cacheConsistent").value(true));
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        UUID a = createAccount("api-carol", 10_000);
        UUID b = createAccount("api-dave", 0);
        mvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destAccountId":"%s",
                                 "amountMinorUnits":100,"currency":"USD"}
                                """.formatted(a, b)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(get("/api/v1/transactions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void auditorCannotMoveMoney() throws Exception {
        String auditor = jwt.issueToken("auditor", List.of("AUDITOR"));
        mvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + auditor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerName":"mallory","currency":"USD","initialBalanceMinorUnits":0}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void validationErrorsAreStructured() throws Exception {
        mvc.perform(post("/api/v1/accounts")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ownerName":"","currency":"USDD","initialBalanceMinorUnits":-5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void insufficientFundsMapsTo422() throws Exception {
        UUID a = createAccount("api-erin", 100);
        UUID b = createAccount("api-frank", 0);
        mvc.perform(post("/api/v1/transfers")
                        .header("Authorization", "Bearer " + operatorToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"%s","destAccountId":"%s",
                                 "amountMinorUnits":99999,"currency":"USD"}
                                """.formatted(a, b)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(
                        "https://ledgerflow.dev/problems/insufficient-funds"));
    }
}

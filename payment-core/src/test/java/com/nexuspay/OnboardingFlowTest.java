package com.nexuspay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexuspay.support.PostgresTestcontainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1.3 "done when": enough exists to make a payment.
 * <p>
 * Walks the whole slice — Controller to Application Service to Domain to
 * Repository — against a real PostgreSQL, so the entities, the Flyway schema
 * and the CHECK constraints are all proven to agree with each other. A mocked
 * repository would prove none of that.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class OnboardingFlowTest {

    /** A well-known test PAN. CLAUDE.md: synthetic data only, never a real card. */
    private static final String TEST_PAN = "4111111111111111";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a customer, account, card, merchant and terminal can all be created")
    void theHappyPath() throws Exception {
        UUID customerId = createCustomer("hana.tanaka+%s@example.com".formatted(UUID.randomUUID()));
        UUID accountId = createAccount(customerId, "100000", "JPY");
        UUID cardId = createCard(accountId, TEST_PAN);
        UUID merchantId = createMerchant();
        UUID terminalId = createTerminal(merchantId, "TERM-001");

        assertThat(customerId).isNotNull();
        assertThat(accountId).isNotNull();
        assertThat(cardId).isNotNull();
        assertThat(merchantId).isNotNull();
        assertThat(terminalId).isNotNull();

        mvc.perform(get("/cards/{id}", cardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.panMasked").value("411111******1111"));
    }

    @Test
    @DisplayName("the raw PAN never appears in a response")
    void panIsNeverEchoed() throws Exception {
        UUID accountId = createAccount(createCustomer(uniqueEmail()), "100000", "JPY");

        // A PAN of its own: tokens are deterministic, so reusing another test's
        // PAN would be refused as a duplicate registration.
        String pan = "4111111111111145";

        MvcResult result = mvc.perform(post("/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","pan":"%s","cardScheme":"VISA",
                                 "expiryMonth":12,"expiryYear":2030}
                                """.formatted(accountId, pan)))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        assertThat(body).doesNotContain(pan);
        assertThat(body)
                .as("not even the token should be exposed — it is an internal handle")
                .doesNotContain("tok_");
        assertThat(body).contains("411111******1145");
    }

    @Test
    @DisplayName("registering the same card twice is refused")
    void duplicateCardRefused() throws Exception {
        UUID accountId = createAccount(createCustomer(uniqueEmail()), "100000", "JPY");
        String pan = "4111111111111129";

        createCard(accountId, pan);

        mvc.perform(post("/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","pan":"%s","cardScheme":"VISA",
                                 "expiryMonth":12,"expiryYear":2030}
                                """.formatted(accountId, pan)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));
    }

    // ------------------------------------------------------------------
    // Card lifecycle through the API
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a card can be blocked, unblocked, and cancelled irreversibly")
    void cardLifecycle() throws Exception {
        UUID accountId = createAccount(createCustomer(uniqueEmail()), "100000", "JPY");
        UUID cardId = createCard(accountId, "4111111111111137");

        mvc.perform(post("/cards/{id}/block", cardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));

        mvc.perform(post("/cards/{id}/unblock", cardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mvc.perform(post("/cards/{id}/cancel", cardId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        mvc.perform(post("/cards/{id}/unblock", cardId))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
    }

    // ------------------------------------------------------------------
    // Error contract
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown currency is a client error, not a server fault")
    void unknownCurrencyIsFourHundred() throws Exception {
        UUID customerId = createCustomer(uniqueEmail());

        // "ZZZ" passes every pattern check and then fails inside
        // Currency.getInstance. Without Currencies.parse this would be a 500.
        mvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","accountType":"CURRENT",
                                 "openingBalance":1000,"currency":"ZZZ"}
                                """.formatted(customerId)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_CURRENCY"));
    }

    @Test
    @DisplayName("a JPY amount with minor units is rejected at the edge")
    void jpyCannotHaveMinorUnits() throws Exception {
        UUID customerId = createCustomer(uniqueEmail());

        mvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","accountType":"CURRENT",
                                 "openingBalance":1000.50,"currency":"JPY"}
                                """.formatted(customerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a malformed PAN fails bean validation with a field-level message")
    void malformedPanIsRejected() throws Exception {
        UUID accountId = createAccount(createCustomer(uniqueEmail()), "100000", "JPY");

        mvc.perform(post("/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","pan":"4111","cardScheme":"VISA",
                                 "expiryMonth":12,"expiryYear":2030}
                                """.formatted(accountId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("pan"));
    }

    @Test
    @DisplayName("an unknown card is a 404 carrying a correlation id")
    void unknownCardIsNotFound() throws Exception {
        mvc.perform(get("/cards/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ENTITY_NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
    }

    @Test
    @DisplayName("a caller-supplied correlation id is echoed back unchanged")
    void correlationIdIsHonoured() throws Exception {
        String supplied = UUID.randomUUID().toString();

        mvc.perform(get("/cards/{id}", UUID.randomUUID()).header("X-Correlation-Id", supplied))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.correlationId").value(supplied));
    }

    @Test
    @DisplayName("a duplicate customer email is refused")
    void duplicateEmailRefused() throws Exception {
        String email = uniqueEmail();
        createCustomer(email);

        mvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Someone Else","email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String uniqueEmail() {
        return "customer+%s@example.com".formatted(UUID.randomUUID());
    }

    private UUID createCustomer(String email) throws Exception {
        return idFrom(mvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Hana Tanaka","email":"%s"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andReturn(), "customerId");
    }

    private UUID createAccount(UUID customerId, String openingBalance, String currency) throws Exception {
        return idFrom(mvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"customerId":"%s","accountType":"CURRENT",
                                 "openingBalance":%s,"currency":"%s"}
                                """.formatted(customerId, openingBalance, currency)))
                .andExpect(status().isCreated())
                .andReturn(), "accountId");
    }

    private UUID createCard(UUID accountId, String pan) throws Exception {
        return idFrom(mvc.perform(post("/cards")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"accountId":"%s","pan":"%s","cardScheme":"VISA",
                                 "expiryMonth":12,"expiryYear":2030}
                                """.formatted(accountId, pan)))
                .andExpect(status().isCreated())
                .andReturn(), "cardId");
    }

    private UUID createMerchant() throws Exception {
        return idFrom(mvc.perform(post("/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalName":"Ramen Ichiban","mcc":"5812",
                                 "country":"JP","settlementCurrency":"JPY"}
                                """))
                .andExpect(status().isCreated())
                .andReturn(), "merchantId");
    }

    private UUID createTerminal(UUID merchantId, String ref) throws Exception {
        return idFrom(mvc.perform(post("/merchants/{id}/terminals", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"terminalRef":"%s"}
                                """.formatted(ref)))
                .andExpect(status().isCreated())
                .andReturn(), "terminalId");
    }

    private UUID idFrom(MvcResult result, String field) throws Exception {
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get(field).asText());
    }
}

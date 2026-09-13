package com.nexuspay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexuspay.payment.domain.PaymentRepository;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1.5: the API is safe to retry.
 * <p>
 * The scenario being defended against is not a misbehaving client. It is an
 * ordinary one whose response was lost — a timeout, a dropped connection, a
 * train entering a tunnel. It cannot tell "the payment was never created" from
 * "the payment was created and I never heard", and its only safe move is to
 * send the request again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class IdempotencyTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PaymentRepository payments;

    // ------------------------------------------------------------------
    // The three requirements from PHASES.md
    // ------------------------------------------------------------------

    @Test
    @DisplayName("same key + same body returns the same payment, and creates only one")
    void sameKeySameBodyReplaysTheResponse() throws Exception {
        Fixture fixture = fixture();
        String key = newKey();
        String body = paymentBody(fixture, 5000);

        String first = postPayment(key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = postPayment(key, body).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(second)
                .as("the retry must receive the original response verbatim")
                .isEqualTo(first);

        assertThat(payments.findByCardId(fixture.cardId()))
                .as("the retry must not create a second payment")
                .hasSize(1);
    }

    @Test
    @DisplayName("same key + different body is refused")
    void sameKeyDifferentBodyIsRejected() throws Exception {
        Fixture fixture = fixture();
        String key = newKey();

        postPayment(key, paymentBody(fixture, 5000)).andExpect(status().isCreated());

        // Without this check the client would receive a successful response
        // describing a ¥5,000 payment while believing it had made a ¥9,000 one.
        postPayment(key, paymentBody(fixture, 9000))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_BODY"));

        assertThat(payments.findByCardId(fixture.cardId())).hasSize(1);
    }

    /**
     * Two identical requests in flight at the same instant — the double-submit,
     * or a client retrying before the first attempt has finished.
     */
    @Test
    @DisplayName("two concurrent identical requests create exactly one payment")
    void concurrentDuplicatesCreateOnePayment() throws Exception {
        Fixture fixture = fixture();
        String key = newKey();
        String body = paymentBody(fixture, 5000);

        CyclicBarrier startTogether = new CyclicBarrier(2);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        Callable<Void> attempt = () -> {
            startTogether.await();
            int statusCode = postPayment(key, body).andReturn().getResponse().getStatus();
            if (statusCode == 201) {
                created.incrementAndGet();
            } else if (statusCode == 409) {
                conflicted.incrementAndGet();
            } else {
                throw new AssertionError("unexpected status " + statusCode);
            }
            return null;
        };

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (Future<Void> result : pool.invokeAll(List.of(attempt, attempt))) {
                result.get();
            }
        }

        assertThat(payments.findByCardId(fixture.cardId()))
                .as("the cardholder must be charged once, whatever the timing")
                .hasSize(1);

        // Either the loser saw IN_PROGRESS (409) or the winner had already
        // finished and it replayed (201). Both are correct; the invariant is
        // that only one payment exists.
        assertThat(created.get() + conflicted.get()).isEqualTo(2);
        assertThat(created.get()).isBetween(1, 2);
    }

    // ------------------------------------------------------------------
    // Contract details
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a missing Idempotency-Key is a client error, not a server fault")
    void missingKeyIsRejected() throws Exception {
        Fixture fixture = fixture();

        mvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(fixture, 5000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

        assertThat(payments.findByCardId(fixture.cardId())).isEmpty();
    }

    @Test
    @DisplayName("a blank Idempotency-Key is refused")
    void blankKeyIsRejected() throws Exception {
        Fixture fixture = fixture();

        mvc.perform(post("/payments")
                        .header("Idempotency-Key", "   ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(fixture, 5000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("keys are scoped per endpoint, so one key can be used on two operations")
    void keysAreScopedPerEndpoint() throws Exception {
        Fixture fixture = fixture();
        String sharedKey = newKey();

        UUID paymentId = idFrom(postPayment(sharedKey, paymentBody(fixture, 5000))
                .andExpect(status().isCreated()).andReturn(), "paymentId");

        // The same key on the capture endpoint must not replay the create
        // response — it is a different operation entirely.
        mvc.perform(post("/payments/{id}/capture", paymentId)
                        .header("Idempotency-Key", sharedKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"));
    }

    @Test
    @DisplayName("a retried capture does not capture twice")
    void captureIsIdempotent() throws Exception {
        Fixture fixture = fixture();
        UUID paymentId = idFrom(postPayment(newKey(), paymentBody(fixture, 5000))
                .andExpect(status().isCreated()).andReturn(), "paymentId");

        String captureKey = newKey();

        String first = mvc.perform(post("/payments/{id}/capture", paymentId)
                        .header("Idempotency-Key", captureKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        // Without idempotency this second call would hit the state machine and
        // fail with CAPTURED -> CAPTURED. Replaying is the friendlier and more
        // correct answer: the client's intent was satisfied the first time.
        String second = mvc.perform(post("/payments/{id}/capture", paymentId)
                        .header("Idempotency-Key", captureKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("a failed operation releases its key, so a corrected retry can succeed")
    void failureReleasesTheKey() throws Exception {
        Fixture fixture = fixture();
        UUID paymentId = idFrom(postPayment(newKey(), paymentBody(fixture, 5000))
                .andExpect(status().isCreated()).andReturn(), "paymentId");

        String key = newKey();

        // Over-capture: rejected by the domain, so nothing commits.
        mvc.perform(post("/payments/{id}/capture", paymentId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":6000}"))
                .andExpect(status().isUnprocessableEntity());

        // The same key must now be usable, because burning it would leave the
        // client unable to ever complete this operation.
        mvc.perform(post("/payments/{id}/capture", paymentId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedAmount").value(5000));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private record Fixture(UUID cardId, UUID merchantId) {
    }

    private static final AtomicInteger PAN_SEQUENCE = new AtomicInteger(500_000);

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    private org.springframework.test.web.servlet.ResultActions postPayment(String key, String body) throws Exception {
        return mvc.perform(post("/payments")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String paymentBody(Fixture fixture, long yen) {
        return """
                {"cardId":"%s","merchantId":"%s","amount":%d,"currency":"JPY"}
                """.formatted(fixture.cardId(), fixture.merchantId(), yen);
    }

    private Fixture fixture() throws Exception {
        UUID customerId = idFrom(mvc.perform(post("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Hana Tanaka","email":"idem+%s@example.com"}
                        """.formatted(UUID.randomUUID()))).andReturn(), "customerId");

        UUID accountId = idFrom(mvc.perform(post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerId":"%s","accountType":"CURRENT","openingBalance":1000000,"currency":"JPY"}
                        """.formatted(customerId))).andReturn(), "accountId");

        UUID cardId = idFrom(mvc.perform(post("/cards")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountId":"%s","pan":"%s","cardScheme":"VISA","expiryMonth":12,"expiryYear":2030}
                        """.formatted(accountId, "411111" + String.format("%010d", PAN_SEQUENCE.getAndIncrement()))))
                .andReturn(), "cardId");

        UUID merchantId = idFrom(mvc.perform(post("/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"legalName":"Ramen Ichiban","mcc":"5812","country":"JP","settlementCurrency":"JPY"}
                        """)).andReturn(), "merchantId");

        return new Fixture(cardId, merchantId);
    }

    private UUID idFrom(MvcResult result, String field) throws Exception {
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get(field).asText());
    }
}

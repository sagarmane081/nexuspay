package com.nexuspay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexuspay.common.error.BusinessRuleViolationException;
import com.nexuspay.common.money.Money;
import com.nexuspay.payment.application.PaymentService;
import com.nexuspay.payment.domain.PaymentRepository;
import com.nexuspay.payment.domain.PaymentStatus;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1.4 end to end: authorize, capture, refund and reverse over the API,
 * against a real PostgreSQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainer.class)
class PaymentFlowTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a ¥5,000 payment authorizes and captures")
    void authorizeAndCapture() throws Exception {
        Fixture fixture = fixture();

        UUID paymentId = createPayment(fixture, 5000);

        mvc.perform(get("/payments/{id}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.amount").value(5000))
                .andExpect(jsonPath("$.authorizedAmount").value(5000))
                .andExpect(jsonPath("$.authCode").isNotEmpty())
                .andExpect(jsonPath("$.businessDate").isNotEmpty());

        mvc.perform(post("/payments/{id}/capture", paymentId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.capturedAmount").value(5000));
    }

    @Test
    @DisplayName("the hotel case: authorize ¥30,000, capture ¥18,000")
    void partialCapture() throws Exception {
        Fixture fixture = fixture();
        UUID paymentId = createPayment(fixture, 30000);

        mvc.perform(post("/payments/{id}/capture", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":18000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.authorizedAmount").value(30000))
                .andExpect(jsonPath("$.capturedAmount").value(18000));
    }

    @Test
    @DisplayName("capturing more than was authorized is refused")
    void overCaptureRefused() throws Exception {
        Fixture fixture = fixture();
        UUID paymentId = createPayment(fixture, 5000);

        mvc.perform(post("/payments/{id}/capture", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":6000}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    @DisplayName("a blocked card is declined, and the decline says why")
    void blockedCardIsDeclined() throws Exception {
        Fixture fixture = fixture();
        mvc.perform(post("/cards/{id}/block", fixture.cardId())).andExpect(status().isOk());

        UUID paymentId = createPayment(fixture, 5000);

        mvc.perform(get("/payments/{id}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.declineReason").value(org.hamcrest.Matchers.containsString("BLOCKED")))
                .andExpect(jsonPath("$.authorizedAmount").doesNotExist());
    }

    @Test
    @DisplayName("an authorized payment can be reversed")
    void reverseAnAuthorization() throws Exception {
        Fixture fixture = fixture();
        UUID paymentId = createPayment(fixture, 5000);

        mvc.perform(post("/payments/{id}/reverse", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"terminal timed out\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));
    }

    @Test
    @DisplayName("a settled payment must be refunded rather than reversed")
    void settledPaymentCannotBeReversed() throws Exception {
        Fixture fixture = fixture();
        UUID paymentId = settledPayment(fixture, 5000, 5000);

        mvc.perform(post("/payments/{id}/reverse", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"customer complained\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("refunded rather than reversed")));
    }

    @Test
    @DisplayName("a settled payment can be refunded, and over-refunding is refused")
    void refundAndOverRefund() throws Exception {
        Fixture fixture = fixture();
        UUID paymentId = settledPayment(fixture, 5000, 5000);

        mvc.perform(post("/payments/{id}/refund", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":2000,\"reason\":\"partial return\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(2000));

        mvc.perform(get("/payments/{id}", paymentId))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.refundedAmount").value(2000));

        // 2000 already returned, so 3001 more would exceed the 5000 capture.
        mvc.perform(post("/payments/{id}/refund", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":3001,\"reason\":\"too much\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("BUSINESS_RULE_VIOLATION"));

        // Exactly the remainder is fine, and completes the refund.
        mvc.perform(post("/payments/{id}/refund", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":3000,\"reason\":\"rest of it\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/payments/{id}", paymentId))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundedAmount").value(5000));
    }

    /**
     * The race the pessimistic lock exists to prevent.
     * <p>
     * Two refunds of ¥3,000 against a ¥5,000 capture arrive at the same instant.
     * Each is individually valid. Without {@code SELECT ... FOR UPDATE} both
     * read {@code refundedAmount = 0}, both conclude they fit, and ¥6,000 is
     * returned against a ¥5,000 charge — money created from nothing.
     */
    @Test
    @DisplayName("two concurrent refunds cannot together exceed the captured amount")
    void concurrentRefundsCannotOverdraw() throws Exception {
        Fixture fixture = fixture();
        UUID paymentId = settledPayment(fixture, 5000, 5000);

        CyclicBarrier startTogether = new CyclicBarrier(2);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        Callable<Void> refundThreeThousand = () -> {
            startTogether.await();
            try {
                paymentService.refund(paymentId, Money.of(3000, "JPY"), "concurrent");
                succeeded.incrementAndGet();
            } catch (BusinessRuleViolationException e) {
                rejected.incrementAndGet();
            }
            return null;
        };

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Void>> results = pool.invokeAll(List.of(refundThreeThousand, refundThreeThousand));
            for (Future<Void> result : results) {
                result.get();
            }
        }

        assertThat(succeeded.get()).as("exactly one refund should win").isEqualTo(1);
        assertThat(rejected.get()).as("the other must be rejected").isEqualTo(1);

        assertThat(paymentRepository.findById(paymentId).orElseThrow().refundedAmount())
                .as("¥3,000 refunded, never ¥6,000")
                .isEqualTo(Money.of(3000, "JPY"));
    }

    @Test
    @DisplayName("the state machine is enforced through the API, not just the domain")
    void cannotCaptureAnUnauthorizedPayment() throws Exception {
        Fixture fixture = fixture();
        UUID paymentId = createPayment(fixture, 5000);

        mvc.perform(post("/payments/{id}/reverse", paymentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"changed mind\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/payments/{id}/capture", paymentId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("the payment carries the caller's correlation id")
    void correlationIdFlowsIntoThePayment() throws Exception {
        Fixture fixture = fixture();
        String supplied = UUID.randomUUID().toString();

        MvcResult result = mvc.perform(post("/payments")
                        .header("X-Correlation-Id", supplied)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cardId":"%s","merchantId":"%s","terminalId":"%s",
                                 "amount":5000,"currency":"JPY"}
                                """.formatted(fixture.cardId(), fixture.merchantId(), fixture.terminalId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correlationId").value(supplied))
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Correlation-Id")).isEqualTo(supplied);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private record Fixture(UUID cardId, UUID merchantId, UUID terminalId) {
    }

    /** A fresh customer, account, card, merchant and terminal for one test. */
    private Fixture fixture() throws Exception {
        UUID customerId = idFrom(mvc.perform(post("/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Hana Tanaka","email":"payer+%s@example.com"}
                        """.formatted(UUID.randomUUID()))).andReturn(), "customerId");

        UUID accountId = idFrom(mvc.perform(post("/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"customerId":"%s","accountType":"CURRENT","openingBalance":1000000,"currency":"JPY"}
                        """.formatted(customerId))).andReturn(), "accountId");

        // Tokens are deterministic, so each test needs its own PAN.
        String pan = uniqueTestPan();
        UUID cardId = idFrom(mvc.perform(post("/cards")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountId":"%s","pan":"%s","cardScheme":"VISA","expiryMonth":12,"expiryYear":2030}
                        """.formatted(accountId, pan))).andReturn(), "cardId");

        UUID merchantId = idFrom(mvc.perform(post("/merchants")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"legalName":"Ramen Ichiban","mcc":"5812","country":"JP","settlementCurrency":"JPY"}
                        """)).andReturn(), "merchantId");

        UUID terminalId = idFrom(mvc.perform(post("/merchants/{id}/terminals", merchantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"terminalRef":"TERM-%s"}
                        """.formatted(UUID.randomUUID().toString().substring(0, 8)))).andReturn(), "terminalId");

        return new Fixture(cardId, merchantId, terminalId);
    }

    private static final AtomicInteger PAN_SEQUENCE = new AtomicInteger(1);

    private static String uniqueTestPan() {
        // 411111 prefix keeps it an obvious Visa test number; the tail varies.
        return "411111" + String.format("%010d", PAN_SEQUENCE.getAndIncrement());
    }

    private UUID createPayment(Fixture fixture, long yen) throws Exception {
        return idFrom(mvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cardId":"%s","merchantId":"%s","terminalId":"%s",
                                 "amount":%d,"currency":"JPY"}
                                """.formatted(fixture.cardId(), fixture.merchantId(), fixture.terminalId(), yen)))
                .andExpect(status().isCreated())
                .andReturn(), "paymentId");
    }

    /**
     * Advances a payment to SETTLED. Clearing and settlement are batch
     * processes that arrive in Phase 4, so the service methods they will drive
     * are called directly here.
     */
    private UUID settledPayment(Fixture fixture, long authorized, long captured) throws Exception {
        UUID paymentId = createPayment(fixture, authorized);

        paymentService.capture(paymentId, Money.of(captured, "JPY"));
        paymentService.markCleared(paymentId);
        paymentService.markSettled(paymentId);

        assertThat(paymentRepository.findById(paymentId).orElseThrow().status())
                .isEqualTo(PaymentStatus.SETTLED);
        return paymentId;
    }

    private UUID idFrom(MvcResult result, String field) throws Exception {
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(node.get(field).asText());
    }
}

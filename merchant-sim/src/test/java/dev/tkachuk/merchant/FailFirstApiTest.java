package dev.tkachuk.merchant;

import static dev.tkachuk.merchant.SignedWebhooks.paymentEvent;
import static dev.tkachuk.merchant.SignedWebhooks.webhook;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** merchant-sim.fail-first answers the first deliveries of each webhook 503, so the gateway's retries show. */
@SpringBootTest(properties = {"merchant-sim.webhook-secret=" + SignedWebhooks.SECRET, "merchant-sim.fail-first=1"})
@AutoConfigureMockMvc
class FailFirstApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldTakeWebhookOnlyFromSecondDelivery() throws Exception {
        String messageId = UUID.randomUUID().toString();
        String body = paymentEvent("payment.declined", UUID.randomUUID().toString(), "DECLINED",
                "2026-11-25T10:00:00Z");

        mockMvc.perform(webhook(messageId, body)).andExpect(status().isServiceUnavailable());
        mockMvc.perform(webhook(messageId, body)).andExpect(status().isNoContent());

        mockMvc.perform(get("/events"))
                .andExpect(jsonPath("$[?(@.webhookId == '%s')].deliveries", messageId).value(2))
                .andExpect(jsonPath("$[?(@.webhookId == '%s')].status", messageId).value("DECLINED"));
    }
}

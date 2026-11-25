package dev.tkachuk.merchant;

import static dev.tkachuk.merchant.SignedWebhooks.paymentEvent;
import static dev.tkachuk.merchant.SignedWebhooks.webhook;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "merchant-sim.webhook-secret=" + SignedWebhooks.SECRET)
@AutoConfigureMockMvc
class WebhookApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldTakeSignedWebhookOnce() throws Exception {
        String messageId = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();
        String body = paymentEvent("payment.succeeded", paymentId, "SUCCEEDED", "2026-11-25T10:00:00Z");

        mockMvc.perform(webhook(messageId, body)).andExpect(status().isNoContent());
        mockMvc.perform(webhook(messageId, body)).andExpect(status().isNoContent());

        mockMvc.perform(get("/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.webhookId == '%s')].deliveries", messageId).value(2))
                .andExpect(jsonPath("$[?(@.webhookId == '%s')].status", messageId).value("SUCCEEDED"));
    }

    @Test
    void shouldRefuseWebhookSignedForAnotherBody() throws Exception {
        String body = paymentEvent("payment.succeeded", UUID.randomUUID().toString(), "SUCCEEDED",
                "2026-11-25T10:00:00Z");

        mockMvc.perform(webhook(UUID.randomUUID().toString(), body).content(body.replace("SUCCEEDED", "DECLINED")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("no signature")));
    }

    @Test
    void shouldRefuseUnsignedWebhook() throws Exception {
        mockMvc.perform(post("/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRefuseSignedBodyThatIsNoPaymentEvent() throws Exception {
        mockMvc.perform(webhook(UUID.randomUUID().toString(), "not json")).andExpect(status().isBadRequest());
        mockMvc.perform(webhook(UUID.randomUUID().toString(), "{\"type\": \"payment.succeeded\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldShowStatusFromWebhooksOnReturnPage() throws Exception {
        String paymentId = UUID.randomUUID().toString();
        mockMvc.perform(get("/return").param("paymentId", paymentId))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("waiting for the gateway's webhook")))
                .andExpect(content().string(containsString("http-equiv=\"refresh\"")));

        mockMvc.perform(webhook(UUID.randomUUID().toString(),
                paymentEvent("payment.succeeded", paymentId, "SUCCEEDED", "2026-11-25T10:00:00Z")));

        mockMvc.perform(get("/return").param("paymentId", paymentId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<b>SUCCEEDED</b>")))
                .andExpect(content().string(not(containsString("http-equiv"))));
    }

    @Test
    void shouldNeedPaymentIdOnReturnPage() throws Exception {
        mockMvc.perform(get("/return")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/return").param("paymentId", "<script>"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("<script>"))));
    }
}

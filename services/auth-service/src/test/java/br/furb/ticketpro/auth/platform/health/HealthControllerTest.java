package br.furb.ticketpro.auth.platform.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

  @Autowired private MockMvc mvc;

  @Test
  void liveReturnsUp() throws Exception {
    mvc.perform(get("/health/live"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("{\"status\":\"up\"}", true));
  }

  @Test
  void readyReturnsUpWithEmptyChecks() throws Exception {
    mvc.perform(get("/health/ready"))
        .andExpect(status().isOk())
        .andExpect(content().json("{\"status\":\"up\",\"checks\":{}}", true));
  }

  @Test
  void healthRejectsPost() throws Exception {
    mvc.perform(post("/health/live")).andExpect(status().isMethodNotAllowed());
  }
}

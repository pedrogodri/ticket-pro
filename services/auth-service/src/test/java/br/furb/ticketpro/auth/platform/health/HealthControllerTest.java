package br.furb.ticketpro.auth.platform.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(HealthController.class)
@Import(HealthControllerTest.Probes.class)
class HealthControllerTest {

  static final AtomicBoolean DB_UP = new AtomicBoolean(true);
  static final AtomicBoolean BROKER_UP = new AtomicBoolean(true);

  @TestConfiguration
  static class Probes {
    @Bean
    DatabaseProbe databaseProbe() {
      return DB_UP::get;
    }

    @Bean
    BrokerProbe brokerProbe() {
      return BROKER_UP::get;
    }
  }

  @Autowired private MockMvc mvc;

  @BeforeEach
  void reset() {
    DB_UP.set(true);
    BROKER_UP.set(true);
  }

  @Test
  void liveIgnoresDependencies() throws Exception {
    DB_UP.set(false);
    mvc.perform(get("/health/live"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(content().json("{\"status\":\"up\"}", true));
  }

  @Test
  void readyReportsDatabaseAndBroker() throws Exception {
    mvc.perform(get("/health/ready"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json("{\"status\":\"up\",\"checks\":{\"db\":\"up\",\"broker\":\"up\"}}", true));
  }

  @Test
  void brokerDownIsDegradedButReady() throws Exception {
    BROKER_UP.set(false);
    mvc.perform(get("/health/ready"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .json(
                    "{\"status\":\"up\",\"checks\":{\"db\":\"up\",\"broker\":\"degraded\"}}",
                    true));
  }

  @Test
  void databaseDownIsNotReady() throws Exception {
    DB_UP.set(false);
    mvc.perform(get("/health/ready"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            content()
                .json(
                    "{\"status\":\"down\",\"checks\":{\"db\":\"down\",\"broker\":\"up\"}}", true));
  }

  @Test
  void healthRejectsPost() throws Exception {
    mvc.perform(post("/health/live")).andExpect(status().isMethodNotAllowed());
  }
}

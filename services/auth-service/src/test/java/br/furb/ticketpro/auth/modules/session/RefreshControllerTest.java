package br.furb.ticketpro.auth.modules.session;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.furb.ticketpro.auth.platform.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** RFC 0007 §5.2 over HTTP: the new pair, or the generic 401 problem. */
@WebMvcTest(controllers = RefreshController.class)
@Import(ApiExceptionHandler.class)
class RefreshControllerTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private RefreshService refresh;

  @Test
  void refreshReturnsTheNewPair() throws Exception {
    when(refresh.refresh(eq("old-token"), anyString()))
        .thenReturn(new LoginResult("access2", "refresh2", 900));
    mvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"old-token\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("access2"))
        .andExpect(jsonPath("$.refreshToken").value("refresh2"))
        .andExpect(jsonPath("$.expiresIn").value(900));
  }

  @Test
  void invalidTokenIsTheGeneric401() throws Exception {
    when(refresh.refresh(anyString(), anyString())).thenThrow(new InvalidCredentialsException());
    mvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"whatever\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  void missingOrBlankTokenIs401WithoutCallingTheService() throws Exception {
    mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"  \"}"))
        .andExpect(status().isUnauthorized());
    verify(refresh, never()).refresh(anyString(), anyString());
  }
}

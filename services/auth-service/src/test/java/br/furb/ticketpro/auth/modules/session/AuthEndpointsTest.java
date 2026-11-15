package br.furb.ticketpro.auth.modules.session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.furb.ticketpro.auth.modules.registration.EmailAlreadyRegisteredException;
import br.furb.ticketpro.auth.modules.registration.RegistrationController;
import br.furb.ticketpro.auth.modules.registration.RegistrationService;
import br.furb.ticketpro.auth.platform.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** RFC 0002 §5.1: register and login over HTTP, problem+json errors (padroes §4.1). */
@WebMvcTest(controllers = {RegistrationController.class, LoginController.class})
@Import(ApiExceptionHandler.class)
class AuthEndpointsTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private RegistrationService registration;
  @MockitoBean private LoginService login;

  private static final String USER = "0193a6f0-1111-7000-8000-000000000001";

  @Test
  void registerReturns201WithUserId() throws Exception {
    when(registration.register(eq("ana@exemplo.com"), eq("correct horse"), eq("Ana"), anyString()))
        .thenReturn(USER);
    mvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"ana@exemplo.com\",\"password\":\"correct horse\",\"name\":\"Ana\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.userId").value(USER));
  }

  @Test
  void duplicateEmailIs409Problem() throws Exception {
    when(registration.register(anyString(), anyString(), anyString(), anyString()))
        .thenThrow(new EmailAlreadyRegisteredException());
    mvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"ANA@exemplo.com\",\"password\":\"correct horse\",\"name\":\"Ana\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value("CONFLICT"))
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  void shortPasswordAndExtraFieldsAre400() throws Exception {
    mvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"ana@exemplo.com\",\"password\":\"1234567\",\"name\":\"Ana\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"ana@exemplo.com\",\"password\":\"correct horse\",\"name\":\"Ana\",\"role\":\"admin\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    mvc.perform(
            post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"not-an-email\",\"password\":\"correct horse\",\"name\":\"Ana\"}"))
        .andExpect(status().isBadRequest());
    verify(registration, never()).register(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void loginReturnsTokens() throws Exception {
    when(login.login("ana@exemplo.com", "correct horse"))
        .thenReturn(new LoginResult("access", "refresh", 900));
    mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"ana@exemplo.com\",\"password\":\"correct horse\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("access"))
        .andExpect(jsonPath("$.refreshToken").value("refresh"))
        .andExpect(jsonPath("$.expiresIn").value(900));
  }

  @Test
  void wrongPasswordAndUnknownEmailGiveTheSame401() throws Exception {
    when(login.login(anyString(), anyString())).thenThrow(new InvalidCredentialsException());
    String wrong =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"ana@exemplo.com\",\"password\":\"wrong pass\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String unknown =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"nobody@exemplo.com\",\"password\":\"wrong pass\"}"))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();
    org.assertj.core.api.Assertions.assertThat(
            unknown.replaceAll("\"correlationId\":\"[^\"]*\"", ""))
        .isEqualTo(wrong.replaceAll("\"correlationId\":\"[^\"]*\"", ""));
    verify(login, org.mockito.Mockito.times(2)).login(anyString(), any());
  }
}

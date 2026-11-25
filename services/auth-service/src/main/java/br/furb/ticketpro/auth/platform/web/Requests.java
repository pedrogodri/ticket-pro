package br.furb.ticketpro.auth.platform.web;

import java.util.Locale;
import java.util.regex.Pattern;

/** Input validation shared by the controllers; failures name the field (padroes §4). */
public final class Requests {

  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

  private Requests() {}

  public static String email(String value) {
    String email = text(value, "email", 3, 254).toLowerCase(Locale.ROOT);
    if (!EMAIL.matcher(email).matches()) {
      throw new ValidationException("email must be a valid e-mail address");
    }
    return email;
  }

  /** RFC 0002 §5.1: 8 to 128 characters, no composition rules (NIST 800-63B). */
  public static String password(String value) {
    if (value == null || value.length() < 8 || value.length() > 128) {
      throw new ValidationException("password must have between 8 and 128 characters");
    }
    return value;
  }

  public static String text(String value, String field, int min, int max) {
    String v = value == null ? "" : value.trim();
    if (v.length() < min || v.length() > max) {
      throw new ValidationException(
          field + " must have between " + min + " and " + max + " characters");
    }
    return v;
  }
}
